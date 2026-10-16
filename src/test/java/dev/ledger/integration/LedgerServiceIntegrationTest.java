package dev.ledger.integration;

import dev.ledger.service.LedgerService;
import dev.ledger.domain.*;
import dev.ledger.storage.memory.InMemoryStorage;
import dev.ledger.testing.ForwardingStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
/** Service rules against the real memory engine: audit, atomic rejection retention, overflow and commit failure. */
class LedgerServiceIntegrationTest {
    @Test void openingFundingAndTransfersAreBalancedAndAuditable() {
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account a = ledger.createAccount(100);
        Account b = ledger.createAccount(0);
        TransferResult result = ledger.transfer("payment", new Transfer(a.id(), b.id(), 30));
        assertEquals(Status.POSTED, result.status());
        assertEquals(70, balance(ledger, a));
        assertEquals(30, balance(ledger, b));
        assertEquals(2, ledger.journal(a.id()).orElseThrow().size());
        assertEquals(1, ledger.journal(b.id()).orElseThrow().size());
        assertEquals(ledger.journal(a.id()).orElseThrow().getLast(), ledger.journal(b.id()).orElseThrow().getFirst());
        assertJournalMatchesBalance(ledger, a);
        assertJournalMatchesBalance(ledger, b);
        assertThrows(UnsupportedOperationException.class, () -> result.postings().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.journal(a.id()).orElseThrow().clear());
    }

    // Later funding must not recalculate an insufficient-funds result under an already reserved key.
    @Test void failuresAreAtomicAndRetryReturnsOriginalFailureAfterFunding() {
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account a = ledger.createAccount(0);
        Account b = ledger.createAccount(10);
        Transfer command = new Transfer(a.id(), b.id(), 5);
        TransferResult failure = ledger.transfer("failed", command);
        assertEquals(Status.INSUFFICIENT_FUNDS, failure.status());
        assertEquals(0, balance(ledger, a));
        assertEquals(10, balance(ledger, b));
        assertTrue(ledger.journal(a.id()).orElseThrow().isEmpty());
        ledger.transfer("fund", new Transfer(b.id(), a.id(), 10));
        assertSame(failure, ledger.transfer("failed", command));
        assertEquals(10, balance(ledger, a));
        assertTrue(failure.postings().isEmpty());
    }

    // Capacity rejection must happen before either wallet or journal changes.
    @Test void overflowDoesNotDebitSourceOrAppendEntries() {
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account a = ledger.createAccount(10);
        Account b = ledger.createAccount(Long.MAX_VALUE);
        assertEquals(Status.BALANCE_OVERFLOW, ledger.transfer("overflow", new Transfer(a.id(), b.id(), 1)).status());
        assertEquals(10, balance(ledger, a));
        assertEquals(Long.MAX_VALUE, balance(ledger, b));
        assertEquals(1, ledger.journal(a.id()).orElseThrow().size());
        assertEquals(1, ledger.journal(b.id()).orElseThrow().size());
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidAmountsAreRemembered(long amount) {
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account a = ledger.createAccount(10);
        Account b = ledger.createAccount(0);
        Transfer command = new Transfer(a.id(), b.id(), amount);
        TransferResult result = ledger.transfer("invalid", command);
        assertEquals(Status.INVALID_TRANSFER, result.status());
        assertSame(result, ledger.transfer("invalid", command));
        assertEquals(10, balance(ledger, a));
        assertEquals(0, balance(ledger, b));
    }

    @ParameterizedTest @ValueSource(strings = {"null-source", "self-existing"})
    void invalidAccountReferencesAreRememberedWithoutMutation(String scenario) {
        LedgerService ledger = new LedgerService(new InMemoryStorage());
        Account from = ledger.createAccount(0);
        Account to = ledger.createAccount(Long.MAX_VALUE);
        Transfer command = switch (scenario) {
            case "null-source" -> new Transfer(null, to.id(), 1);
            case "self-existing" -> new Transfer(from.id(), from.id(), 1);
            default -> throw new AssertionError("Unknown scenario: " + scenario);
        };
        var sourceJournal = ledger.journal(from.id()).orElseThrow();
        var destinationJournal = ledger.journal(to.id()).orElseThrow();

        TransferResult result = ledger.transfer("invalid-reference", command);
        assertEquals(Status.INVALID_TRANSFER, result.status());
        assertSame(result, ledger.transfer("invalid-reference", command));
        assertThrows(IdempotencyConflict.class,
                () -> ledger.transfer("invalid-reference", new Transfer(to.id(), from.id(), 1)));
        assertEquals(from, ledger.account(from.id()).orElseThrow());
        assertEquals(to, ledger.account(to.id()).orElseThrow());
        assertEquals(sourceJournal, ledger.journal(from.id()).orElseThrow());
        assertEquals(destinationJournal, ledger.journal(to.id()).orElseThrow());
    }

    // Inject a normal runtime failure before assignment and prove retry retention prevents a second execution.
    @Test void exceptionBeforeCommitUnblocksRetriesWithoutMutationOrRerunning() {
        AtomicInteger executions = new AtomicInteger();
        LedgerService ledger = new LedgerService(ForwardingStorage.beforeCommit(new InMemoryStorage(),
                _ -> { executions.incrementAndGet(); throw new IllegalStateException("injected"); }));
        Account a = ledger.createAccount(10), b = ledger.createAccount(0);
        Transfer command = new Transfer(a.id(), b.id(), 1);
        assertThrows(IllegalStateException.class, () -> ledger.transfer("exception", command));
        assertThrows(CompletionException.class, () -> ledger.transfer("exception", command));
        assertEquals(1, executions.get());
        assertEquals(10, balance(ledger, a));
        assertEquals(0, balance(ledger, b));
        assertTrue(ledger.journal(b.id()).orElseThrow().isEmpty());
    }

    // Read through the public snapshot API so tests do not bypass the implementation's locking.
    private static long balance(LedgerService ledger, Account account) { return ledger.account(account.id()).orElseThrow().balance(); }

    private static void assertJournalMatchesBalance(LedgerService ledger, Account account) {
        BigInteger sum = BigInteger.ZERO;
        for (JournalTransaction transaction : ledger.journal(account.id()).orElseThrow()) {
            assertEquals(0, Math.addExact(transaction.postings().getFirst().amount(), transaction.postings().getLast().amount()));
            for (Posting posting : transaction.postings()) {
                if (posting.accountId().equals(account.id().toString())) sum = sum.add(BigInteger.valueOf(posting.amount()));
            }
        }
        assertEquals(BigInteger.valueOf(balance(ledger, account)), sum);
    }

}
