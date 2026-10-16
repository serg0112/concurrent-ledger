package dev.ledger.controller;

import dev.ledger.domain.Status;
import dev.ledger.logging.RequestLogging;
import dev.ledger.controller.validation.LedgerRequestReader;
import dev.ledger.service.LedgerService;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.Objects;

public final class LedgerController {
    private final LedgerService ledger;
    private final LedgerRequestReader requests;

    public LedgerController(LedgerService ledger, LedgerRequestReader requests) {
        this.ledger = Objects.requireNonNull(ledger);
        this.requests = Objects.requireNonNull(requests);
    }

    public void registerRoutes(JavalinConfig config) {
        config.routes.post("/accounts", this::createAccount);
        config.routes.get("/accounts/{id}/balance", this::balance);
        config.routes.get("/accounts/{id}/entries", this::entries);
        config.routes.post("/transfers", this::transfer);
    }

    private void createAccount(Context ctx) {
        var account = ledger.createAccount(requests.readInitialBalance(ctx));
        ctx.header("Location", "/accounts/" + account.id() + "/balance").status(201).json(account);
    }

    private void balance(Context ctx) {
        var account = ledger.account(LedgerRequestReader.parseAccountId(ctx.pathParam("id")))
                .orElseThrow(() -> new NotFoundResponse("Account not found"));
        ctx.json(account);
    }

    private void entries(Context ctx) {
        var entries = ledger.journal(LedgerRequestReader.parseAccountId(ctx.pathParam("id")))
                .orElseThrow(() -> new NotFoundResponse("Account not found"));
        ctx.json(entries);
    }

    // Read transport-valid data before reserving a key; the ledger owns all remembered business outcomes.
    private void transfer(Context ctx) {
        var command = requests.readTransfer(ctx);
        var result = ledger.transfer(command.key(), command.toTransfer());
        // Correlate this attempt with its stable transfer ID without changing the replayable JSON body.
        RequestLogging.recordTransfer(ctx, result.id());
        ctx.status(statusFor(result.status())).json(result);
    }

    // Translate typed business outcomes at the HTTP boundary instead of embedding HTTP codes in the core.
    private static int statusFor(Status status) {
        return switch (status) {
            case POSTED -> 200;
            case INVALID_TRANSFER -> 400;
            case ACCOUNT_NOT_FOUND -> 404;
            case INSUFFICIENT_FUNDS, BALANCE_OVERFLOW -> 422;
        };
    }
}
