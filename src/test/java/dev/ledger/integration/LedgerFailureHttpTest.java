package dev.ledger.integration;

import dev.ledger.service.LedgerService;
import dev.ledger.server.LedgerHttpServer;
import dev.ledger.storage.memory.InMemoryStorage;
import dev.ledger.testing.ForwardingStorage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Confirms internal execution failures keep their retry identity through HTTP error translation. */
class LedgerFailureHttpTest {
    // Internal argument and state exceptions must both remain server errors, unlike explicit caller validation.
    static Stream<RuntimeException> internalFailures() {
        return Stream.of(new IllegalArgumentException("internal pre-commit failure"),
                new IllegalStateException("internal pre-commit failure"));
    }

    @ParameterizedTest @MethodSource("internalFailures")
    void originalAndRetryReturnIdenticalGeneric500WithoutExecutingTwice(RuntimeException failure) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        LedgerService ledger = new LedgerService(ForwardingStorage.beforeCommit(new InMemoryStorage(),
                _ -> { executions.incrementAndGet(); throw failure; }));
        var source = ledger.createAccount(10);
        var destination = ledger.createAccount(0);
        try (LedgerHttpServer server = new LedgerHttpServer(ledger, new InetSocketAddress("127.0.0.1", 0));
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String body = "{\"fromAccountId\":\"" + source.id() + "\",\"toAccountId\":\"" + destination.id() + "\",\"amount\":1}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/transfers"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Idempotency-Key", "internal-failure").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            // Use the exact same HTTP request again; neither message privacy nor key retention may depend on the attempt.
            var original = client.send(request, HttpResponse.BodyHandlers.ofString());
            var retry = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, original.statusCode());
            assertEquals(original.statusCode(), retry.statusCode());
            assertEquals("{\"error\":\"Internal error; retry with the same idempotency key\"}", original.body());
            assertEquals(original.body(), retry.body());
            assertEquals(1, executions.get());
            assertEquals(10, ledger.account(source.id()).orElseThrow().balance());
            assertEquals(0, ledger.account(destination.id()).orElseThrow().balance());
            assertEquals(1, ledger.journal(source.id()).orElseThrow().size());
            assertTrue(ledger.journal(destination.id()).orElseThrow().isEmpty());
        }
    }
}
