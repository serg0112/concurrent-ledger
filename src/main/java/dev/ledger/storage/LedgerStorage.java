package dev.ledger.storage;

import dev.ledger.domain.Account;
import dev.ledger.domain.JournalTransaction;
import dev.ledger.domain.Transfer;
import dev.ledger.domain.TransferResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** Atomic storage mechanisms; the service supplies all money and command-conflict decisions. */
public interface LedgerStorage {
    /** Publish the prepared account and opening entry together; return false without changes on ID collision. */
    boolean tryCreate(Account account, Optional<JournalTransaction> openingEntry);

    /** Linearizable immutable snapshot of one account; separate reads are not a global snapshot. */
    Optional<Account> account(UUID id);

    /** Immutable, consistent account history in oldest-first order. */
    Optional<List<JournalTransaction>> journal(UUID id);

    /**
     * Apply once under this key, or return its retained outcome. Existing commands are passed to
     * checkExistingCommand BEFORE waiting, allowing the service to reject conflicts immediately.
     * Only the owner invokes decide, against protected account snapshots. An Update requires two
     * distinct existing accounts. Missing/null references appear as empty snapshots. Retain keys
     * and outcomes for the engine's entire state lifetime, including unchanged business results.
     *
     * <p>The key, original command, result, balances and journal changes are one atomic storage
     * operation. A durable engine must commit them together, not persist an independent key
     * reservation followed by a separate transfer. Pending coordination is an internal concern.
     * Unrelated account pairs must be able to progress independently.
     *
     * <p>Callbacks must not perform external side effects or reenter storage. An engine may
     * reevaluate a decision before commit; only the committed result is published. Runtime failures
     * before commit leave accounts unchanged and retain an exceptional outcome, releasing waiters
     * without executing again. Exception wrappers may differ between owner and retries.
     * Guarantees apply for the engine's documented state lifetime; fatal VM/resource failures and
     * process termination are outside the memory engine's guarantee.
     */
    TransferResult apply(String key, Transfer command, Consumer<Transfer> checkExistingCommand,
                         Function<Accounts, Change> decide);

    /** Account values protected by the current storage operation, never independently read by the service. */
    record Accounts(Optional<Account> source, Optional<Account> destination) {}

    /** Prepared effects contain no mutable storage state. */
    sealed interface Change permits NoChange, Update {
        TransferResult result();
    }

    /** Remember a result without changing balances or journals. */
    record NoChange(TransferResult result) implements Change {
        public NoChange { Objects.requireNonNull(result); }
    }

    /** The service prepares new balances and one shared journal transaction; storage installs them together. */
    record Update(long sourceBalance, long destinationBalance, JournalTransaction transaction,
                  TransferResult result) implements Change {
        public Update {
            Objects.requireNonNull(transaction);
            Objects.requireNonNull(result);
        }
    }
}
