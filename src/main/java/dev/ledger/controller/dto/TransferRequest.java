package dev.ledger.controller.dto;

import dev.ledger.domain.Transfer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record TransferRequest(
        @NotNull(message = "Idempotency-Key is required")
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}",
                message = "Idempotency-Key must be 1-128 ASCII letters, digits or ._:-") String key,
        @NotNull(message = "fromAccountId must be a string")
        @Pattern(regexp = ACCOUNT_ID_PATTERN, message = "Invalid account UUID") String fromAccountId,
        @NotNull(message = "toAccountId must be a string")
        @Pattern(regexp = ACCOUNT_ID_PATTERN, message = "Invalid account UUID") String toAccountId,
        @NotNull(message = "amount must be an integer JSON number in minor units") Long amount) {
    public static final String ACCOUNT_ID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    public Transfer toTransfer() {
        return new Transfer(UUID.fromString(fromAccountId), UUID.fromString(toAccountId), amount);
    }
}
