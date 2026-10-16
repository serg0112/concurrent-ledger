package dev.ledger.domain;

public final class IdempotencyConflict extends RuntimeException {
    public IdempotencyConflict() { super("Idempotency key already belongs to a different transfer"); }
}
