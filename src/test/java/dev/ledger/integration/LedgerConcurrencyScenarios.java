package dev.ledger.integration;

import dev.ledger.service.LedgerService;
import dev.ledger.domain.Account;
import dev.ledger.domain.IdempotencyConflict;
import dev.ledger.domain.JournalTransaction;
import dev.ledger.domain.Posting;
import dev.ledger.domain.Status;
import dev.ledger.domain.Transfer;
import dev.ledger.domain.TransferResult;
import dev.ledger.storage.memory.InMemoryStorage;
import dev.ledger.testing.Await;
import dev.ledger.testing.ChildJvmOnly;
import dev.ledger.testing.ForwardingStorage;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Executed only in a child JVM; the parent enforces the deadline even if cleanup deadlocks. */
@ChildJvmOnly
public final class LedgerConcurrencyScenarios {
    // Short coordination bounds expose missing arrivals; worker batches share a longer budget inside the outer process deadline.
    private static final Duration START_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration WORKER_TIMEOUT = Duration.ofSeconds(20);
    private static final String OPENING_EQUITY = "external:opening-equity";

    @Test
    void thousandsOfContendingDebitsCannotOverspendOrLoseCredits() throws Exception {
        int workerCount = 32;
        int attemptsPerWorker = 250;
        long openingFunds = 1_000;
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account source = ledger.createAccount(openingFunds);
        Account destination = ledger.createAccount(0);
        AtomicInteger posted = new AtomicInteger();
        // Count the coordinator too: no worker starts transferring until every intended contender reaches this gate.
        CyclicBarrier start = new CyclicBarrier(workerCount + 1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workerCount)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                int number = worker;
                workers.add(pool.submit(() -> {
                    awaitStart(start);
                    for (int i = 0; i < attemptsPerWorker; i++) {
                        TransferResult result = ledger.transfer(number + ":" + i, new Transfer(source.id(), destination.id(), 1));
                        if (result.status() == Status.POSTED) posted.incrementAndGet();
                        else assertEquals(Status.INSUFFICIENT_FUNDS, result.status());
                        assertTrue(balance(ledger, source) >= 0);
                    }
                }));
            }
            awaitStart(start);
            awaitAll("competing debits", workers);
        }
        assertEquals(openingFunds, posted.get());
        assertEquals(0, balance(ledger, source));
        assertEquals(openingFunds, balance(ledger, destination));
        assertEquals(openingFunds, ledger.journal(destination.id()).orElseThrow().size());
        assertJournalsConsistent(ledger, List.of(source, destination));
    }

    @Test
    void oppositeDirectionsAndConcurrentBalanceReadsPreserveMoneyWithoutDeadlock() throws Exception {
        int workerCount = 24;
        int transfersPerWorker = 400;
        long openingFunds = 10_000;
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account a = ledger.createAccount(openingFunds);
        Account b = ledger.createAccount(openingFunds);
        CyclicBarrier start = new CyclicBarrier(workerCount + 1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workerCount)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                int number = worker;
                workers.add(pool.submit(() -> {
                    awaitStart(start);
                    Transfer command = number % 2 == 0 ? new Transfer(a.id(), b.id(), 1) : new Transfer(b.id(), a.id(), 1);
                    for (int i = 0; i < transfersPerWorker; i++) {
                        assertEquals(Status.POSTED, ledger.transfer(number + ":" + i, command).status());
                        assertTrue(balance(ledger, a) >= 0);
                        assertTrue(balance(ledger, b) >= 0);
                    }
                }));
            }
            awaitStart(start);
            awaitAll("opposite-direction transfers", workers);
        }
        assertEquals(openingFunds, balance(ledger, a));
        assertEquals(openingFunds, balance(ledger, b));
        assertEquals(workerCount * transfersPerWorker + 1, ledger.journal(a.id()).orElseThrow().size());
        assertJournalsConsistent(ledger, List.of(a, b));
    }

    @Test
    void simultaneousRetriesShareExactlyOneResult() throws Exception {
        int requestCount = 1_000;
        long openingFunds = 100;
        long amount = 10;
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account a = ledger.createAccount(openingFunds);
        Account b = ledger.createAccount(0);
        Transfer command = new Transfer(a.id(), b.id(), amount);
        CyclicBarrier start = new CyclicBarrier(requestCount + 1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TransferResult>> requests = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                requests.add(pool.submit(() -> {
                    awaitStart(start);
                    return ledger.transfer("same", command);
                }));
            }
            awaitStart(start);
            awaitAll("same-key retries", requests);
            TransferResult original = requests.getFirst().resultNow();
            assertEquals(Status.POSTED, original.status());
            for (Future<TransferResult> request : requests) assertSame(original, request.resultNow());
        }
        assertEquals(openingFunds - amount, balance(ledger, a));
        assertEquals(amount, balance(ledger, b));
        assertEquals(1, ledger.journal(b.id()).orElseThrow().size());
        assertJournalsConsistent(ledger, List.of(a, b));
    }

    @Test
    void inFlightRetryAcrossServicesWaitsAndUnrelatedTransferCanFinish() throws Exception {
        CountDownLatch insideCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        // Intercept the prepared decision inside the engine's critical section: both locks are held and no state is installed yet.
        var storage = ForwardingStorage.beforeCommit(new InMemoryStorage(), _ -> {
            if (commits.incrementAndGet() == 1) {
                insideCommit.countDown();
                Await.latch(releaseCommit, WORKER_TIMEOUT, "Paused transfer was not released");
            }
        });
        LedgerService ledger = new LedgerService(storage);
        LedgerService otherService = new LedgerService(storage);
        Account a = ledger.createAccount(100), b = ledger.createAccount(0);
        Account c = ledger.createAccount(100), d = ledger.createAccount(0);
        Transfer command = new Transfer(a.id(), b.id(), 10);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TransferResult> original = pool.submit(() -> ledger.transfer("in-flight", command));
            try {
                // The owner is inside the critical section before any retry or reader is submitted.
                Await.latch(insideCommit, START_TIMEOUT, "Owner did not reach the commit gate");
                var retry = observe(pool, () -> otherService.transfer("in-flight", command));
                var sourceBalance = observe(pool, () -> balance(ledger, a));
                var destinationBalance = observe(pool, () -> balance(ledger, b));
                var sourceJournal = observe(pool, () -> ledger.journal(a.id()).orElseThrow());
                var destinationJournal = observe(pool, () -> ledger.journal(b.id()).orElseThrow());

                // Observe real wait frames, not merely task submission or a scheduler-dependent short timeout.
                awaitWaitingIn("matching retry", retry, CompletableFuture.class, "join");
                awaitWaitingIn("source balance reader", sourceBalance, ReentrantLock.class, "lock");
                awaitWaitingIn("destination balance reader", destinationBalance, ReentrantLock.class, "lock");
                awaitWaitingIn("source journal reader", sourceJournal, ReentrantLock.class, "lock");
                awaitWaitingIn("destination journal reader", destinationJournal, ReentrantLock.class, "lock");
                List<Future<?>> waiting = List.of(retry.future(), sourceBalance.future(), destinationBalance.future(),
                        sourceJournal.future(), destinationJournal.future());
                for (Future<?> request : waiting) assertFalse(request.isDone());
                assertThrows(IdempotencyConflict.class, () -> otherService.transfer("in-flight", new Transfer(a.id(), b.id(), 11)));

                // Completing this call before release rules out a global ledger lock held across the owner's commit.
                Future<TransferResult> unrelated = pool.submit(() -> otherService.transfer("unrelated", new Transfer(c.id(), d.id(), 7)));
                assertEquals(Status.POSTED, unrelated.get(START_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS).status());
                assertFalse(original.isDone(), "Unrelated accounts must progress while the owner is paused");
                releaseCommit.countDown();

                List<Future<?>> completed = new ArrayList<>(waiting);
                completed.add(original);
                awaitAll("paused transfer and its readers", completed);
                var posted = original.resultNow();
                assertEquals(Status.POSTED, posted.status());
                assertSame(posted, retry.future().resultNow());
                assertEquals(90, sourceBalance.future().resultNow());
                assertEquals(10, destinationBalance.future().resultNow());
                // Readers must return the committed journal transaction after release, with no half-applied history.
                var transaction = new JournalTransaction(posted.id(), posted.postings());
                assertEquals(2, sourceJournal.future().resultNow().size());
                assertEquals(transaction, sourceJournal.future().resultNow().getLast());
                assertEquals(List.of(transaction), destinationJournal.future().resultNow());
            } finally {
                releaseCommit.countDown();
            }
        }
        assertEquals(2, commits.get());
        assertEquals(90, balance(ledger, a));
        assertEquals(10, balance(ledger, b));
        assertEquals(93, balance(ledger, c));
        assertEquals(7, balance(ledger, d));
        assertJournalsConsistent(ledger, List.of(a, b, c, d));
    }

    @Test
    void competingCreditsCannotOverflowDestinationOrDebitRejectedSource() throws Exception {
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account firstSource = ledger.createAccount(1);
        Account secondSource = ledger.createAccount(1);
        Account destination = ledger.createAccount(Long.MAX_VALUE - 1);
        List<Account> sources = List.of(firstSource, secondSource);
        List<Transfer> commands = sources.stream().map(source -> new Transfer(source.id(), destination.id(), 1)).toList();
        CyclicBarrier start = new CyclicBarrier(sources.size() + 1);
        try (ExecutorService pool = Executors.newFixedThreadPool(sources.size())) {
            List<Future<TransferResult>> requests = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                int number = i;
                requests.add(pool.submit(() -> {
                    awaitStart(start);
                    return ledger.transfer("overflow-" + number, commands.get(number));
                }));
            }
            awaitStart(start);
            awaitAll("competing destination credits", requests);
            // Either source may win, but exactly one result must post and the other must be a remembered overflow rejection.
            var results = requests.stream().map(Future::resultNow).toList();
            assertEquals(1, results.stream().filter(result -> result.status() == Status.POSTED).count());
            assertEquals(1, results.stream().filter(result -> result.status() == Status.BALANCE_OVERFLOW).count());
            assertEquals(Long.MAX_VALUE, balance(ledger, destination));
            var destinationJournal = ledger.journal(destination.id()).orElseThrow();
            assertEquals(2, destinationJournal.size());
            for (int i = 0; i < sources.size(); i++) {
                TransferResult result = results.get(i);
                var sourceJournal = ledger.journal(sources.get(i).id()).orElseThrow();
                if (result.status() == Status.POSTED) {
                    assertEquals(0, balance(ledger, sources.get(i)));
                    assertEquals(2, sourceJournal.size());
                    assertEquals(result.id(), sourceJournal.getLast().id());
                    assertEquals(sourceJournal.getLast(), destinationJournal.getLast());
                } else {
                    assertEquals(1, balance(ledger, sources.get(i)));
                    assertEquals(1, sourceJournal.size());
                    assertTrue(result.postings().isEmpty());
                    assertTrue(destinationJournal.stream().noneMatch(transaction -> transaction.id().equals(result.id())));
                }
                // Replaying the winner must not credit again; replaying the loser must not re-evaluate its rejection.
                assertSame(result, ledger.transfer("overflow-" + i, commands.get(i)));
            }
        }
        assertJournalsConsistent(ledger, List.of(firstSource, secondSource, destination));
    }

    @Test
    void fanOutCannotOverspendSharedSourceOrLoseDestinationCredits() throws Exception {
        int destinationCount = 16;
        int attemptsPerDestination = 200;
        long openingFunds = 1_000;
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account source = ledger.createAccount(openingFunds);
        List<Account> destinations = new ArrayList<>();
        for (int i = 0; i < destinationCount; i++) destinations.add(ledger.createAccount(0));
        AtomicInteger posted = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(destinationCount + 1);
        try (ExecutorService pool = Executors.newFixedThreadPool(destinationCount)) {
            List<Future<?>> workers = new ArrayList<>();
            for (Account destination : destinations) workers.add(pool.submit(() -> {
                awaitStart(start);
                for (int i = 0; i < attemptsPerDestination; i++) {
                    var result = ledger.transfer(destination.id() + ":" + i, new Transfer(source.id(), destination.id(), 1));
                    if (result.status() == Status.POSTED) posted.incrementAndGet();
                    else assertEquals(Status.INSUFFICIENT_FUNDS, result.status());
                    assertTrue(balance(ledger, source) >= 0);
                }
            }));
            awaitStart(start);
            awaitAll("fan-out transfers", workers);
        }
        assertEquals(openingFunds, posted.get());
        assertEquals(0, balance(ledger, source));
        assertEquals(openingFunds, destinations.stream().mapToLong(a -> balance(ledger, a)).sum());
        var accounts = new ArrayList<>(destinations);
        accounts.add(source);
        assertJournalsConsistent(ledger, accounts);
    }

    @Test
    void fanInPreservesEveryCreditToSharedDestination() throws Exception {
        int sourceCount = 16;
        int transfersPerSource = 200;
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account destination = ledger.createAccount(0);
        List<Account> sources = new ArrayList<>();
        for (int i = 0; i < sourceCount; i++) sources.add(ledger.createAccount(transfersPerSource));
        CyclicBarrier start = new CyclicBarrier(sourceCount + 1);
        try (ExecutorService pool = Executors.newFixedThreadPool(sourceCount)) {
            List<Future<?>> workers = new ArrayList<>();
            for (Account source : sources) workers.add(pool.submit(() -> {
                awaitStart(start);
                for (int i = 0; i < transfersPerSource; i++) {
                    assertEquals(Status.POSTED, ledger.transfer(source.id() + ":" + i,
                            new Transfer(source.id(), destination.id(), 1)).status());
                }
            }));
            awaitStart(start);
            awaitAll("fan-in transfers", workers);
        }
        int totalTransfers = sourceCount * transfersPerSource;
        assertEquals(totalTransfers, balance(ledger, destination));
        assertEquals(totalTransfers, ledger.journal(destination.id()).orElseThrow().size());
        for (Account source : sources) assertEquals(0, balance(ledger, source));
        var accounts = new ArrayList<>(sources);
        accounts.add(destination);
        assertJournalsConsistent(ledger, accounts);
    }

    @Test
    void threeAccountCyclePreservesMoneyAndJournalsWithoutDeadlock() throws Exception {
        int workerCount = 24;
        int transfersPerWorker = 400;
        long openingFunds = 10_000;
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        List<Account> accounts = List.of(ledger.createAccount(openingFunds), ledger.createAccount(openingFunds), ledger.createAccount(openingFunds));
        CyclicBarrier start = new CyclicBarrier(workerCount + 1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workerCount)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                int number = worker;
                Account from = accounts.get(number % accounts.size()), to = accounts.get((number + 1) % accounts.size());
                workers.add(pool.submit(() -> {
                    awaitStart(start);
                    for (int i = 0; i < transfersPerWorker; i++) {
                        assertEquals(Status.POSTED, ledger.transfer(number + ":" + i, new Transfer(from.id(), to.id(), 1)).status());
                        assertTrue(balance(ledger, from) >= 0);
                    }
                }));
            }
            awaitStart(start);
            awaitAll("three-account cycle", workers);
        }
        int entriesPerAccount = 2 * (workerCount / accounts.size()) * transfersPerWorker + 1;
        for (Account account : accounts) {
            assertEquals(openingFunds, balance(ledger, account));
            assertEquals(entriesPerAccount, ledger.journal(account.id()).orElseThrow().size());
        }
        assertJournalsConsistent(ledger, accounts);
    }

    // Use the normal locked account API so assertions exercise the same visibility boundary as clients.
    private static long balance(LedgerService ledger, Account account) {
        return ledger.account(account.id()).orElseThrow().balance();
    }

    /** Call only after writers finish: separate balance/journal reads are not a global snapshot. */
    private static void assertJournalsConsistent(LedgerService ledger, List<Account> accounts) {
        Map<UUID, JournalTransaction> transactions = new HashMap<>();
        Map<UUID, Set<String>> journalOwners = new HashMap<>();
        BigInteger openingTotal = BigInteger.ZERO;
        BigInteger currentTotal = BigInteger.ZERO;
        for (Account account : accounts) {
            String accountId = account.id().toString();
            Set<UUID> seen = new HashSet<>();
            BigInteger journalBalance = BigInteger.ZERO;
            for (JournalTransaction transaction : ledger.journal(account.id()).orElseThrow()) {
                // Include wallet/transaction context so a failing reconciliation identifies the corrupt audit boundary.
                String context = "account=" + accountId + " transaction=" + transaction.id();
                assertTrue(seen.add(transaction.id()), "Duplicate journal transaction: " + context);
                assertEquals(2, transaction.postings().size(), context);
                assertEquals(BigInteger.ZERO, transaction.postings().stream().map(posting -> BigInteger.valueOf(posting.amount()))
                        .reduce(BigInteger.ZERO, BigInteger::add), "Unbalanced postings: " + context);
                assertTrue(transaction.postings().stream().anyMatch(posting -> posting.accountId().equals(accountId)),
                        "Journal contains an unrelated transaction: " + context);
                var existing = transactions.putIfAbsent(transaction.id(), transaction);
                if (existing != null) assertEquals(existing, transaction, "Transaction differs across journals: " + context);
                journalOwners.computeIfAbsent(transaction.id(), _ -> new HashSet<>()).add(accountId);
                for (Posting posting : transaction.postings()) {
                    if (posting.accountId().equals(accountId)) journalBalance = journalBalance.add(BigInteger.valueOf(posting.amount()));
                }
            }
            // With writers stopped, compare journal sums to balances and accumulate totals without signed-long overflow.
            long currentBalance = balance(ledger, account);
            assertTrue(currentBalance >= 0, "Negative balance: " + accountId);
            assertEquals(BigInteger.valueOf(currentBalance), journalBalance, "Journal differs from balance: " + accountId);
            openingTotal = openingTotal.add(BigInteger.valueOf(account.balance()));
            currentTotal = currentTotal.add(BigInteger.valueOf(currentBalance));
        }
        // Opening issuance has one wallet copy; transfers must have equal copies in both posting owners' journals.
        for (JournalTransaction transaction : transactions.values()) {
            boolean opening = transaction.postings().stream().anyMatch(posting -> posting.accountId().equals(OPENING_EQUITY));
            Set<String> expectedOwners = transaction.postings().stream().map(Posting::accountId)
                    .filter(accountId -> !accountId.equals(OPENING_EQUITY)).collect(Collectors.toSet());
            assertEquals(opening ? 1 : 2, expectedOwners.size(), "Unexpected transaction participants: " + transaction.id());
            assertEquals(expectedOwners, journalOwners.get(transaction.id()), "Missing or extra journal copy: " + transaction.id());
        }
        assertEquals(openingTotal, currentTotal, "Transfers must conserve total wallet money");
    }

    // Bound barrier waits so a missing worker fails explicitly; interruption still preserves the interrupt flag.
    private static void awaitStart(CyclicBarrier start) {
        try {
            start.await(START_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted at worker start barrier", error);
        } catch (BrokenBarrierException | TimeoutException error) {
            throw new AssertionError("Worker start barrier failed; parties=" + start.getParties()
                    + ", broken=" + start.isBroken(), error);
        }
    }

    // Use one absolute deadline per batch, not a fresh full timeout for each future in a long list.
    private static void awaitAll(String scenario, List<? extends Future<?>> workers) throws Exception {
        long deadline = System.nanoTime() + WORKER_TIMEOUT.toNanos();
        for (int i = 0; i < workers.size(); i++) {
            try {
                workers.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException error) {
                throw new AssertionError(scenario + ": worker " + i + " failed", error.getCause());
            } catch (TimeoutException error) {
                throw new AssertionError(scenario + ": worker " + i + " exceeded the shared " + WORKER_TIMEOUT + " deadline", error);
            }
        }
    }

    // Publish the actual executing thread alongside its future so queued work cannot masquerade as a blocked ledger call.
    private static <T> ObservedTask<T> observe(ExecutorService pool, Callable<T> action) {
        var thread = new AtomicReference<Thread>();
        Future<T> future = pool.submit(() -> {
            thread.set(Thread.currentThread());
            return action.call();
        });
        return new ObservedTask<>(future, thread);
    }

    // Poll only until the observation deadline; capture the wait location and report useful state if it never appears.
    private static void awaitWaitingIn(String role, ObservedTask<?> task, Class<?> type, String method) throws InterruptedException {
        long deadline = System.nanoTime() + OBSERVATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            assertFalse(task.future().isDone(), role + " completed before the owner was released; state=" + task.future().state());
            Thread thread = task.thread().get();
            if (thread != null && thread.getState() == Thread.State.WAITING
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(type.getName()) && frame.getMethodName().equals(method))) return;
            Thread.sleep(10);
        }
        Thread thread = task.thread().get();
        fail(role + " did not wait in " + type.getSimpleName() + "." + method + "; "
                + (thread == null ? "task was not scheduled" : "threadState=" + thread.getState()
                + " stack=" + Arrays.toString(thread.getStackTrace())));
    }

    private record ObservedTask<T>(Future<T> future, AtomicReference<Thread> thread) {}
}
