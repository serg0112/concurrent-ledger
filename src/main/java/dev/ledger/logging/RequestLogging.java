package dev.ledger.logging;

import io.javalin.http.Context;
import java.util.Objects;
import java.util.UUID;

public final class RequestLogging {
    private static final String REQUEST_ID = RequestLogging.class.getName() + ".requestId";
    private static final String TRANSFER_ID = RequestLogging.class.getName() + ".transferId";
    private final System.Logger logger;

    public RequestLogging(System.Logger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    public static void prepare(Context ctx) {
        String id = UUID.randomUUID().toString();
        ctx.attribute(REQUEST_ID, id);
        ctx.header("X-Request-ID", id);
    }

    public static void recordTransfer(Context ctx, UUID transferId) {
        ctx.attribute(TRANSFER_ID, transferId);
    }

    public void log(Context ctx, Float executionTimeMs) {
        logger.log(System.Logger.Level.INFO, "event=http_request " + context(ctx)
                + " status=" + ctx.statusCode() + " durationMs=" + executionTimeMs);
    }

    public void failure(Context ctx, Exception failure) {
        logger.log(System.Logger.Level.ERROR, "event=http_error " + context(ctx), failure);
    }

    private static String context(Context ctx) {
        var endpoint = ctx.endpoints().matchedHttpEndpoint();
        String method = ctx.req().getMethod();
        return "requestId=" + Objects.toString(ctx.attribute(REQUEST_ID), "none")
                + " method=" + (method.matches("[A-Z]{1,16}") ? method : "OTHER")
                + " route=" + (endpoint == null ? "<unmatched>" : endpoint.path)
                + " transferId=" + Objects.toString(ctx.attribute(TRANSFER_ID), "none");
    }
}
