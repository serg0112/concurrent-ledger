package dev.ledger.storage.memory;

import dev.ledger.domain.Account;
import dev.ledger.domain.JournalTransaction;
import dev.ledger.domain.Posting;
import dev.ledger.domain.Status;
import dev.ledger.domain.Transfer;
import dev.ledger.domain.TransferResult;
import dev.ledger.storage.LedgerStorage.NoChange;
import dev.ledger.storage.LedgerStorage.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises storage effects directly; the callbacks supply prepared decisions rather than money rules. */
class InMemoryStorageTest {
    private final InMemoryStorage storage = new InMemoryStorage();
    private final Account from = new Account(UUID.randomUUID(), 100);
    private final Account to = new Account(UUID.randomUUID(), 20);
    private final Transfer command = new Transfer(from.id(), to.id(), 7);

    @BeforeEach void createPreparedAccounts() {
        assertTrue(storage.tryCreate(from, Optional.of(opening(from))));
        assertTrue(storage.tryCreate(to, Optional.of(opening(to))));
    }

    @Test void collisionCannotReplaceBalanceOrOpeningJournal() {
        var history = storage.journal(from.id()).orElseThrow();
        assertFalse(storage.tryCreate(new Account(from.id(), 0), Optional.empty()));
        assertEquals(Optional.of(from), storage.account(from.id()));
        assertEquals(history, storage.journal(from.id()).orElseThrow());
    }

    @Test void installsThePreparedUpdateAndSharedJournalTransaction() {
        var update = update();
        var result = storage.apply("posted", command, _ -> fail("New key"), accounts -> {
            assertEquals(Optional.of(from), accounts.source());
            assertEquals(Optional.of(to), accounts.destination());
            return update;
        });
        assertSame(update.result(), result);
        assertEquals(93, storage.account(from.id()).orElseThrow().balance());
        assertEquals(27, storage.account(to.id()).orElseThrow().balance());
        assertSame(update.transaction(), storage.journal(from.id()).orElseThrow().getLast());
        assertSame(update.transaction(), storage.journal(to.id()).orElseThrow().getLast());
    }

    @Test void retainsNoChangeAndLetsServicePolicyCheckExistingCommands() {
        var result = new TransferResult(UUID.randomUUID(), Status.INSUFFICIENT_FUNDS, command, List.of());
        var decisions = new AtomicInteger();
        assertSame(result, storage.apply("rejected", command, _ -> fail("New key"), _ -> {
            decisions.incrementAndGet();
            return new NoChange(result);
        }));
        var different = new Transfer(to.id(), from.id(), 1);
        var policyFailure = new IllegalStateException("Service conflict policy");
        assertSame(policyFailure, assertThrows(IllegalStateException.class,
                () -> storage.apply("rejected", different, original -> {
                    assertEquals(command, original);
                    throw policyFailure;
                }, _ -> fail("An existing key must not be decided again"))));
        assertSame(result, storage.apply("rejected", command, original -> assertEquals(command, original),
                _ -> fail("A matching retry must use its remembered outcome")));
        assertEquals(1, decisions.get());
        assertEquals(Optional.of(from), storage.account(from.id()));
        assertEquals(Optional.of(to), storage.account(to.id()));
        assertEquals(1, storage.journal(from.id()).orElseThrow().size());
        assertEquals(1, storage.journal(to.id()).orElseThrow().size());
    }

    @Test void decisionFailureRetainsTheKeyWithoutMutationAndReleasesLocks() {
        var failure = new IllegalStateException("Decision preparation failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> storage.apply("failed", command, _ -> fail("New key"), _ -> { throw failure; })));
        var replayFailure = assertThrows(CompletionException.class,
                () -> storage.apply("failed", command, _ -> {}, _ -> fail("Failed attempts must not execute again")));
        assertSame(failure, replayFailure.getCause());
        assertEquals(Optional.of(from), storage.account(from.id()));
        assertEquals(Optional.of(to), storage.account(to.id()));
        assertEquals(1, storage.journal(from.id()).orElseThrow().size());
        assertEquals(1, storage.journal(to.id()).orElseThrow().size());
        var update = update();
        assertSame(update.result(), storage.apply("another", command, _ -> {}, _ -> update));
    }

    @Test void journalSnapshotsStayImmutableAfterLaterCommits() {
        var original = storage.journal(from.id()).orElseThrow();
        assertThrows(UnsupportedOperationException.class, original::clear);
        assertThrows(UnsupportedOperationException.class, original.getFirst().postings()::clear);
        storage.apply("new", command, _ -> {}, _ -> update());
        assertEquals(1, original.size());
        assertEquals(2, storage.journal(from.id()).orElseThrow().size());
    }

    private Update update() {
        var transaction = new JournalTransaction(UUID.randomUUID(), List.of(
                new Posting(from.id().toString(), -7), new Posting(to.id().toString(), 7)));
        var result = new TransferResult(transaction.id(), Status.POSTED, command, transaction.postings());
        return new Update(93, 27, transaction, result);
    }

    private static JournalTransaction opening(Account account) {
        return new JournalTransaction(UUID.randomUUID(), List.of(
                new Posting("external:opening-equity", -account.balance()),
                new Posting(account.id().toString(), account.balance())));
    }
}
