package dev.ledger.server;

import dev.ledger.service.LedgerService;
import dev.ledger.storage.memory.InMemoryStorage;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies ownership/cleanup of the real validation provider and the classification of provider failures. */
@Timeout(20)
class ValidationLifecycleTest {
    private static final System.Logger LOG = System.getLogger(ValidationLifecycleTest.class.getName());
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    @Test void oneFactoryIsReusedAndClosedOnceAfterServerStops() throws Exception {
        try (var factory = new TrackedFactory();
             var server = server(factory, ADDRESS);
             var client = HttpClient.newHttpClient()) {
            server.start();
            assertEquals(201, createAccount(client, server).statusCode());
            assertEquals(201, createAccount(client, server).statusCode());
            assertEquals(1, factory.validatorRequests.get());
            assertEquals(0, factory.closes.get());
            server.close();
            server.close();
            assertEquals(1, factory.closes.get());
            assertThrows(IllegalStateException.class, server::start);
        }
    }

    // Occupy a real port so startup failure exercises cleanup through the same path as an unavailable listener.
    @Test void bindFailureClosesFactoryWithoutNeedingCallerCleanup() throws Exception {
        try (var factory = new TrackedFactory(); var occupied = new ServerSocket(0, 1, ADDRESS.getAddress())) {
            var server = server(factory, new InetSocketAddress("127.0.0.1", occupied.getLocalPort()));
            assertThrows(RuntimeException.class, server::start);
            assertEquals(1, factory.closes.get());
            server.close();
            assertEquals(1, factory.closes.get());
        }
    }

    @Test void configurationFailureClosesFactoryAndPreservesOriginalException() {
        try (var factory = new TrackedFactory()) {
            assertThrows(NullPointerException.class, () -> new LedgerHttpServer(null, ADDRESS, LOG, factory.proxy()));
            assertEquals(1, factory.closes.get());
        }
    }

    @Test void providerSetupFailureClosesFactoryAndPreservesOriginalException() {
        try (var factory = new TrackedFactory()) {
            factory.setupFailure = new ValidationException("private setup details");
            var error = assertThrows(ValidationException.class, () -> server(factory, ADDRESS));
            assertSame(factory.setupFailure, error);
            assertEquals(1, factory.closes.get());
        }
    }

    // Validator infrastructure failure is a private server error, not evidence that the client supplied bad data.
    @Test void providerRuntimeFailureReturnsGeneric500RatherThanCallerError() throws Exception {
        try (var factory = new TrackedFactory()) {
            factory.requestFailure = new ValidationException("private provider details");
            try (var server = server(factory, ADDRESS); var client = HttpClient.newHttpClient()) {
                server.start();
                var response = createAccount(client, server);
                assertEquals(500, response.statusCode());
                assertEquals("{\"error\":\"Internal error\"}", response.body());
            }
            assertEquals(1, factory.closes.get());
        }
    }

    @Test void closingBeforeStartAlsoClosesFactory() {
        try (var factory = new TrackedFactory()) {
            server(factory, ADDRESS).close();
            assertEquals(1, factory.closes.get());
        }
    }

    // Inject only the factory seam while retaining the real memory engine and normal server configuration.
    private static LedgerHttpServer server(TrackedFactory factory, InetSocketAddress address) {
        return new LedgerHttpServer(new LedgerService(new InMemoryStorage()), address, LOG, factory.proxy());
    }

    private static HttpResponse<String> createAccount(HttpClient client, LedgerHttpServer server) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/accounts"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Intercepts only provider lifecycle/failure seams; all normal validation uses the real provider. */
    private static final class TrackedFactory implements AutoCloseable {
        private final ValidatorFactory delegate = Validation.buildDefaultValidatorFactory();
        private final AtomicInteger validatorRequests = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private ValidationException setupFailure;
        private ValidationException requestFailure;

        ValidatorFactory proxy() {
            return (ValidatorFactory) Proxy.newProxyInstance(ValidatorFactory.class.getClassLoader(),
                    new Class<?>[]{ValidatorFactory.class}, (_, method, args) -> {
                        if (method.getName().equals("close")) closes.incrementAndGet();
                        // Separate setup failure from per-request failure so both cleanup and HTTP mapping can be checked.
                        if (method.getName().equals("getValidator")) {
                            validatorRequests.incrementAndGet();
                            if (setupFailure != null) throw setupFailure;
                            if (requestFailure != null) {
                                return Proxy.newProxyInstance(Validator.class.getClassLoader(), new Class<?>[]{Validator.class},
                                        (_, _, _) -> { throw requestFailure; });
                            }
                        }
                        try {
                            return method.invoke(delegate, args);
                        // Reflection must expose the provider's actual exception, not an invocation wrapper invented by the test.
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        // Fallback cleanup releases the real provider even when an assertion fails before the server can close.
        @Override public void close() { delegate.close(); }
    }
}
