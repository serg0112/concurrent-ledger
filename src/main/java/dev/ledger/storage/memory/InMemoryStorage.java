package dev.ledger.storage.memory;

import dev.ledger.domain.Account;
import dev.ledger.domain.JournalTransaction;
import dev.ledger.domain.Transfer;
import dev.ledger.domain.TransferResult;
import dev.ledger.storage.LedgerStorage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public final class InMemoryStorage implements LedgerStorage {
    private final ConcurrentHashMap<UUID, Wallet> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Override
    public boolean tryCreate(Account account, Optional<JournalTransaction> openingEntry) {
        Node journal = openingEntry.map(entry -> new Node(entry, null)).orElse(null);
        Wallet wallet = new Wallet(new State(account.balance(), journal));
        return accounts.putIfAbsent(account.id(), wallet) == null;
    }

    @Override
    public Optional<Account> account(UUID id) {
        return snapshot(id).map(state -> new Account(id, state.balance()));
    }

    @Override
    public Optional<List<JournalTransaction>> journal(UUID id) {
        // Immutable links allow traversal after the account lock is released.
        return snapshot(id).map(state -> Stream.iterate(state.journal(), Objects::nonNull, Node::previous)
                .map(Node::transaction).toList().reversed());
    }

    @Override
    public TransferResult apply(String key, Transfer command, Consumer<Transfer> checkExistingCommand,
                                Function<Accounts, Change> decide) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(checkExistingCommand);
        Objects.requireNonNull(decide);
        var candidate = new Attempt(command, new CompletableFuture<>());
        Attempt existing = attempts.putIfAbsent(key, candidate);
        if (existing != null) {
            // Conflict policy belongs to the service and runs before waiting for a pending owner.
            checkExistingCommand.accept(existing.command());
            return existing.result().join();
        }
        try {
            TransferResult result = execute(command, decide);
            // No fallible application work follows state installation; publish after releasing the account locks.
            candidate.result().complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            candidate.result().completeExceptionally(failure);
            throw failure;
        }
    }

    private TransferResult execute(Transfer command, Function<Accounts, Change> decide) {
        Wallet from = wallet(command.fromAccountId());
        Wallet to = wallet(command.toAccountId());
        // Lock available resources in one UUID order, once each. The service decides missing/self/null outcomes.
        boolean sourceFirst = from != null && (to == null || command.fromAccountId().compareTo(command.toAccountId()) <= 0);
        Wallet first = sourceFirst ? from : to;
        Wallet second = from == to ? null : sourceFirst ? to : from;
        try (var _ = HeldLock.acquire(first); var _ = HeldLock.acquire(second)) {
            Change change = Objects.requireNonNull(decide.apply(new Accounts(
                    accountSnapshot(command.fromAccountId(), from), accountSnapshot(command.toAccountId(), to))));
            return switch (change) {
                case NoChange unchanged -> unchanged.result();
                case Update update -> install(from, to, update);
            };
        }
    }

    private TransferResult install(Wallet from, Wallet to, Update update) {
        // This is a storage precondition, not a funds/self-transfer business rejection.
        if (from == null || to == null || from == to) {
            throw new IllegalStateException("An update requires two distinct existing accounts");
        }
        var debited = new State(update.sourceBalance(), new Node(update.transaction(), from.state.journal()));
        var credited = new State(update.destinationBalance(), new Node(update.transaction(), to.state.journal()));
        // All fallible preparation precedes mutation. Readers use these same locks and cannot see half a commit.
        from.state = debited;
        to.state = credited;
        return update.result();
    }

    private Wallet wallet(UUID id) { return id == null ? null : accounts.get(id); }

    private Optional<State> snapshot(UUID id) {
        return Optional.ofNullable(accounts.get(Objects.requireNonNull(id))).map(Wallet::snapshot);
    }

    // Called only while the corresponding wallet lock is held; no second independent read escapes the atomic scope.
    private static Optional<Account> accountSnapshot(UUID id, Wallet wallet) {
        return wallet == null ? Optional.empty() : Optional.of(new Account(id, wallet.state.balance()));
    }

    private static final class Wallet {
        final ReentrantLock lock = new ReentrantLock();
        State state;

        Wallet(State state) { this.state = state; }

        State snapshot() {
            try (var _ = new HeldLock(lock)) { return state; }
        }
    }

    // Resource scopes release locks in reverse acquisition order, including when a decision or hook fails.
    private record HeldLock(ReentrantLock lock) implements AutoCloseable {
        private HeldLock(ReentrantLock lock) {
            this.lock = lock;
            lock.lock();
        }

        static HeldLock acquire(Wallet wallet) { return wallet == null ? null : new HeldLock(wallet.lock); }

        @Override public void close() { lock.unlock(); }
    }

    private record State(long balance, Node journal) {}
    private record Node(JournalTransaction transaction, Node previous) {}
    private record Attempt(Transfer command, CompletableFuture<TransferResult> result) {}
}
