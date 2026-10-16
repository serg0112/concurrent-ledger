package dev.ledger.testing;

import dev.ledger.domain.Account;
import dev.ledger.domain.JournalTransaction;
import dev.ledger.domain.Transfer;
import dev.ledger.domain.TransferResult;
import dev.ledger.storage.LedgerStorage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** Test-only decorator for observing atomic storage boundaries while preserving real engine behavior. */
public class ForwardingStorage implements LedgerStorage {
    private final LedgerStorage delegate;

    public ForwardingStorage(LedgerStorage delegate) { this.delegate = delegate; }

    /**
     * Runs a hook after each decision is prepared and before the delegate installs it, inside the
     * delegate's atomic scope. Throwing leaves accounts unchanged and retains the key with an
     * exceptional outcome, so tests can pause or fail exactly at the commit boundary.
     */
    public static ForwardingStorage beforeCommit(LedgerStorage delegate, Consumer<Change> hook) {
        return new ForwardingStorage(delegate) {
            @Override public TransferResult apply(String key, Transfer command, Consumer<Transfer> checkExistingCommand,
                                                  Function<Accounts, Change> decide) {
                return super.apply(key, command, checkExistingCommand, accounts -> {
                    Change prepared = decide.apply(accounts);
                    hook.accept(prepared);
                    return prepared;
                });
            }
        };
    }

    @Override public boolean tryCreate(Account account, Optional<JournalTransaction> openingEntry) {
        return delegate.tryCreate(account, openingEntry);
    }

    @Override public Optional<Account> account(UUID id) { return delegate.account(id); }

    @Override public Optional<List<JournalTransaction>> journal(UUID id) { return delegate.journal(id); }

    @Override public TransferResult apply(String key, Transfer command, Consumer<Transfer> checkExistingCommand,
                                          Function<Accounts, Change> decide) {
        return delegate.apply(key, command, checkExistingCommand, decide);
    }
}
