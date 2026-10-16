package dev.ledger.domain;

import java.util.UUID;

public record Transfer(UUID fromAccountId, UUID toAccountId, long amount) {}
