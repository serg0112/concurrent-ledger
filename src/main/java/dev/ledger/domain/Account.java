package dev.ledger.domain;

import java.util.UUID;

public record Account(UUID id, long balance) {}
