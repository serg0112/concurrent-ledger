package dev.ledger.service;

import dev.ledger.domain.Account;
import dev.ledger.domain.JournalTransaction;
import dev.ledger.domain.Posting;
import dev.ledger.domain.Transfer;
import dev.ledger.domain.TransferResult;
import dev.ledger.storage.LedgerStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Scripted-storage cases whose guarantees the memory engine cannot express: issuance shape and id-collision retry. */
class LedgerServiceTest {
    @ParameterizedTest @ValueSource(longs = {0, 10, Long.MAX_VALUE})
    void servicePreparesOpeningIssuance(long balance) {
        var storage = new ScriptedStorage();
        Account account = new LedgerService(storage).createAccount(balance);
        assertSame(account, storage.created.getFirst());
        assertEquals(balance, account.balance());
        if (balance == 0) assertTrue(storage.opening.isEmpty());
        else assertEquals(List.of(new Posting("external:opening-equity", -balance),
                new Posting(account.id().toString(), balance)), storage.opening.orElseThrow().postings());
    }

    // A colliding id must be retried with a fresh id rather than replacing or corrupting the existing wallet.
    @Test void accountIdCollisionRetriesWithoutReplacingTheExistingAccount() {
        var storage = new ScriptedStorage();
        storage.collisions = 1;
        Account account = new LedgerService(storage).createAccount(10);
        assertEquals(2, storage.created.size());
        assertNotEquals(storage.created.getFirst().id(), account.id());
        assertSame(account, storage.created.getLast());
        assertEquals(account.id().toString(), storage.opening.orElseThrow().postings().getLast().accountId());
    }

    /** Returns scripted creation outcomes; money rules live in the service and are untested here. */
    private static final class ScriptedStorage implements LedgerStorage {
        final List<Account> created = new ArrayList<>();
        Optional<JournalTransaction> opening;
        int collisions;

        @Override public boolean tryCreate(Account account, Optional<JournalTransaction> entry) {
            created.add(account);
            opening = entry;
            return collisions-- <= 0;
        }

        @Override public Optional<Account> account(UUID id) { return Optional.empty(); }

        @Override public Optional<List<JournalTransaction>> journal(UUID id) { return Optional.empty(); }

        @Override public TransferResult apply(String key, Transfer command, Consumer<Transfer> checkExisting,
                                              Function<Accounts, Change> decide) {
            throw new UnsupportedOperationException("Scripted creation only");
        }
    }
}
