package dev.ledger.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateAccountRequest(
        @NotNull(message = "initialBalance must be an integer JSON number in minor units")
        @PositiveOrZero(message = "initialBalance must be nonnegative") Long initialBalance) {}
