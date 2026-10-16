package dev.ledger.server;

import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import dev.ledger.controller.LedgerController;
import dev.ledger.controller.validation.LedgerRequestReader;
import dev.ledger.domain.IdempotencyConflict;
import dev.ledger.domain.ValidationException;
import dev.ledger.logging.RequestLogging;
import dev.ledger.service.LedgerService;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.MethodNotAllowedResponse;
import io.javalin.json.JavalinGson;
import io.javalin.router.EndpointNotFound;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.eclipse.jetty.io.EofException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LedgerHttpServer implements AutoCloseable {
    private static final int MAX_REQUEST_SIZE_BYTES = 8192;
    private final Javalin app;
    private final ValidatorFactory validation;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LedgerHttpServer(LedgerService ledger, InetSocketAddress address) {
        this(ledger, address, System.getLogger(RequestLogging.class.getName()));
    }

    public LedgerHttpServer(LedgerService ledger, InetSocketAddress address, System.Logger logger) {
        this(ledger, address, logger, Validation.buildDefaultValidatorFactory());
    }

    LedgerHttpServer(LedgerService ledger, InetSocketAddress address, System.Logger logger, ValidatorFactory validation) {
        this.validation = Objects.requireNonNull(validation);
        try {
            var validator = validation.getValidator();
            app = Javalin.create(config -> configure(config, ledger, address, logger, validator));
        } catch (RuntimeException | Error failure) {
            // Construction can fail before an app exists, so close the factory directly and retain the original failure.
            closeAfterFailure(validation, failure);
            throw failure;
        }
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("Server is closed");
        try {
            app.start();
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(this, failure);
            throw failure;
        }
    }

    public int port() { return app.port(); }

    /** Stops HTTP before closing validation; the atomic guard makes repeated cleanup calls harmless. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                app.stop();
            } finally {
                validation.close();
            }
        }
    }

    private static void configure(JavalinConfig config, LedgerService ledger, InetSocketAddress address,
                                  System.Logger logger, Validator validator) {
        Objects.requireNonNull(address);
        config.jetty.host = address.getHostString();
        config.jetty.port = address.getPort();
        config.concurrency.useVirtualThreads = true;
        config.http.maxRequestSize = MAX_REQUEST_SIZE_BYTES;
        config.http.prefer405over404 = true;
        config.router.ignoreTrailingSlashes = false;
        config.startup.showJavalinBanner = false;
        config.startup.showOldJavalinVersionWarning = false;
        config.startup.startupWatcherEnabled = false;

        var json = new GsonBuilder().setStrictness(Strictness.STRICT).create();
        config.jsonMapper(new JavalinGson(json, false));
        var logging = new RequestLogging(logger);
        config.routes.before(ctx -> ctx.header("Cache-Control", "no-store"));
        config.routes.before(RequestLogging::prepare);
        config.requestLogger.http(logging::log);
        new LedgerController(ledger, new LedgerRequestReader(json, validator)).registerRoutes(config);
        registerExceptionHandlers(config, logging);
    }

    private static void registerExceptionHandlers(JavalinConfig config, RequestLogging logging) {
        config.routes.exception(ValidationException.class, (error, ctx) -> replyError(ctx, 400, error.getMessage()));
        config.routes.exception(IdempotencyConflict.class, (error, ctx) -> replyError(ctx, 409, error.getMessage()));
        config.routes.exception(HttpResponseException.class, (error, ctx) -> {
            if (error instanceof MethodNotAllowedResponse) {
                ctx.header("Allow", error.getDetails().getOrDefault("availableMethods", ""));
            }
            replyError(ctx, error.getStatus(), error instanceof EndpointNotFound ? "Route not found" : error.getMessage());
        });
        // Include Jetty EOF subclasses without treating unrelated server-side IOExceptions as client disconnects.
        config.routes.exception(EofException.class, (_, ctx) -> {
            if (!ctx.res().isCommitted()) ctx.status(499).result("");
        });
        config.routes.exception(Exception.class, (error, ctx) -> {
            // Record the cause before attempting delivery, which can itself fail after a committed transfer.
            logging.failure(ctx, error);
            if (!ctx.res().isCommitted()) {
                replyError(ctx, 500, ctx.path().equals("/transfers") && ctx.method().name().equals("POST")
                        ? "Internal error; retry with the same idempotency key" : "Internal error");
            }
        });
    }

    private static void replyError(Context ctx, int status, String message) {
        ctx.status(status).json(Map.of("error", message));
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
