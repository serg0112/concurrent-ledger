package dev.ledger.integration;

import com.google.gson.Gson;
import dev.ledger.service.LedgerService;
import dev.ledger.domain.*;
import dev.ledger.server.LedgerHttpServer;
import dev.ledger.testing.Await;
import dev.ledger.testing.ChildJvmOnly;
import dev.ledger.testing.ForwardingStorage;
import dev.ledger.storage.LedgerStorage;
import dev.ledger.storage.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only adapters expose request/commit boundaries without production HTTP hooks. */
@ChildJvmOnly
public final class LedgerHttpEdgeScenarios {
    private static final Gson JSON = new Gson();

    @Test
    void disconnectAfterCommitBeforeResponse() throws Exception {
        var storage = new InMemoryStorage();
        LedgerService core = new LedgerService(storage);
        Account from = core.createAccount(100), to = core.createAccount(0);
        Transfer command = new Transfer(from.id(), to.id(), 7);
        CountDownLatch committed = new CountDownLatch(1), permitResponse = new CountDownLatch(1);
        AtomicReference<TransferResult> original = new AtomicReference<>();
        LedgerStorage pausedAfterCommit = new ForwardingStorage(storage) {
            // Pause only the first response AFTER the core publishes its result; retries still reach normal ledger logic.
            @Override public TransferResult apply(String key, Transfer transfer, Consumer<Transfer> checkExisting,
                                                   Function<Accounts, Change> decide) {
                TransferResult result = super.apply(key, transfer, checkExisting, decide);
                if (original.compareAndSet(null, result)) {
                    committed.countDown();
                    Await.latch(permitResponse, "Test gate timed out");
                }
                return result;
            }
        };
        try (HttpFixture http = new HttpFixture(new LedgerService(pausedAfterCommit))) {
            try {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", http.server.port()), 5_000);
                    socket.setSoTimeout(5_000);
                    socket.setSoLinger(true, 0); // Close resets the connection without reading a response.
                    // Frame the real HTTP request by UTF-8 byte count so the test disconnects at the intended response boundary.
                    byte[] body = JSON.toJson(command).getBytes(StandardCharsets.UTF_8);
                    String headers = "POST /transfers HTTP/1.1\r\nHost: 127.0.0.1:" + http.server.port()
                            + "\r\nContent-Type: application/json\r\nIdempotency-Key: disconnected"
                            + "\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    Await.latch(committed, "Test gate timed out");
                    assertEquals(Status.POSTED, original.get().status());
                    assertPostedOnce(core, from, to, 7);
                    assertEquals(0, socket.getInputStream().available(), "Server must still be paused before response delivery");
                }
                // The peer has disconnected; only now allow the original handler to respond.
                permitResponse.countDown();
                var retry = http.post("disconnected", command);
                assertEquals(200, retry.statusCode());
                assertEquals(JSON.toJson(original.get()), retry.body());
                assertPostedOnce(core, from, to, 7);
            } finally { permitResponse.countDown(); }
        }
    }

    private static void assertPostedOnce(LedgerService ledger, Account from, Account to, long amount) {
        assertEquals(100 - amount, ledger.account(from.id()).orElseThrow().balance());
        assertEquals(amount, ledger.account(to.id()).orElseThrow().balance());
        assertEquals(2, ledger.journal(from.id()).orElseThrow().size());
        assertEquals(1, ledger.journal(to.id()).orElseThrow().size());
        assertEquals(ledger.journal(from.id()).orElseThrow().getLast(), ledger.journal(to.id()).orElseThrow().getFirst());
    }

    private static final class HttpFixture implements AutoCloseable {
        final LedgerHttpServer server;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpFixture(LedgerService ledger) throws Exception {
            server = new LedgerHttpServer(ledger, new InetSocketAddress("127.0.0.1", 0));
            server.start();
        }
        // A synchronous request is useful after releasing a gate when the next assertion needs the full original outcome.
        HttpResponse<String> post(String key, Transfer command) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/transfers"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(command))).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        // Close client work before server shutdown; a cleanup deadlock is still bounded by the parent harness.
        @Override public void close() { client.close(); server.close(); }
    }
}
