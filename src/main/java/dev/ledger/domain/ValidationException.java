package dev.ledger.domain;

public final class ValidationException extends IllegalArgumentException {
    public ValidationException(String message) { super(message); }
}
