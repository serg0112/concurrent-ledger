package dev.ledger.service;

import dev.ledger.domain.Account;
import dev.ledger.domain.IdempotencyConflict;
import dev.ledger.domain.JournalTransaction;
import dev.ledger.domain.Posting;
import dev.ledger.domain.Status;
import dev.ledger.domain.Transfer;
import dev.ledger.domain.TransferResult;
import dev.ledger.domain.ValidationException;
import dev.ledger.storage.LedgerStorage;
import dev.ledger.storage.LedgerStorage.Accounts;
import dev.ledger.storage.LedgerStorage.Change;
import dev.ledger.storage.LedgerStorage.NoChange;
import dev.ledger.storage.LedgerStorage.Update;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LedgerService {
    private final LedgerStorage storage;

    public LedgerService(LedgerStorage storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    public Account createAccount(long initialBalance) {
        if (initialBalance < 0) throw new ValidationException("initialBalance must be nonnegative");
        while (true) {
            var account = new Account(UUID.randomUUID(), initialBalance);
            if (storage.tryCreate(account, openingEntry(account))) return account;
        }
    }

    public Optional<Account> account(UUID id) {
        return storage.account(Objects.requireNonNull(id));
    }

    public Optional<List<JournalTransaction>> journal(UUID id) {
        return storage.journal(Objects.requireNonNull(id));
    }

    /** Equal retries share the original outcome, including remembered business rejections and failures. */
    public TransferResult transfer(String key, Transfer command) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ValidationException("Idempotency-Key must be 1-128 ASCII letters, digits or ._:-");
        }
        Objects.requireNonNull(command, "transfer");
        // One call owns the whole protocol; the service never reserves a key separately from execution.
        return storage.apply(key, command, existing -> {
            if (!existing.equals(command)) throw new IdempotencyConflict();
        }, accounts -> decide(command, accounts));
    }

    private static Optional<JournalTransaction> openingEntry(Account account) {
        if (account.balance() == 0) return Optional.empty();
        return Optional.of(new JournalTransaction(UUID.randomUUID(), List.of(
                new Posting("external:opening-equity", -account.balance()),
                new Posting(account.id().toString(), account.balance()))));
    }

    private static Change decide(Transfer command, Accounts accounts) {
        // This runs inside storage's atomic scope, including all fallible result/posting preparation.
        UUID id = UUID.randomUUID();
        if (!isWellFormed(command)) return rejected(id, Status.INVALID_TRANSFER, command);

        var from = accounts.source();
        var to = accounts.destination();
        if (from.isEmpty() || to.isEmpty()) return rejected(id, Status.ACCOUNT_NOT_FOUND, command);

        long sourceBalance = from.orElseThrow().balance();
        long destinationBalance = to.orElseThrow().balance();
        if (sourceBalance < command.amount()) return rejected(id, Status.INSUFFICIENT_FUNDS, command);
        if (destinationBalance > Long.MAX_VALUE - command.amount()) return rejected(id, Status.BALANCE_OVERFLOW, command);

        return post(id, command, sourceBalance, destinationBalance);
    }

    private static boolean isWellFormed(Transfer command) {
        return command.fromAccountId() != null && command.toAccountId() != null
                && !command.fromAccountId().equals(command.toAccountId()) && command.amount() > 0;
    }

    private static Update post(UUID id, Transfer command, long sourceBalance, long destinationBalance) {
        var transaction = new JournalTransaction(id, List.of(
                new Posting(command.fromAccountId().toString(), -command.amount()),
                new Posting(command.toAccountId().toString(), command.amount())));
        var result = new TransferResult(id, Status.POSTED, command, transaction.postings());
        return new Update(sourceBalance - command.amount(), destinationBalance + command.amount(), transaction, result);
    }

    private static NoChange rejected(UUID id, Status status, Transfer command) {
        return new NoChange(new TransferResult(id, status, command, List.of()));
    }
}
