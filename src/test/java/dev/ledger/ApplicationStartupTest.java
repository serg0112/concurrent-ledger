package dev.ledger;

import com.google.gson.JsonParser;
import dev.ledger.testing.ForkedJvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the configured application in a child JVM: real wiring, environment settings, endpoints
 * and externally bounded termination. A hung exit cannot trap this worker either.
 */
@Timeout(90)
class ApplicationStartupTest {
    // Require the completed log line so a torn write cannot publish a truncated port number.
    private static final Pattern LISTENING = Pattern.compile("Ledger listening at http://([^:]+):(\\d+)\\R");
    private static final Duration STARTUP_DEADLINE = Duration.ofSeconds(30);

    @Test
    void applicationStartsServesHttpAndTerminatesWithinExternalDeadlines() throws Exception {
        Path output = Files.createTempFile("ledger-application-", ".log");
        Process child = null;
        try {
            var launcher = new ProcessBuilder(ForkedJvm.javaExecutable(), "-cp", ForkedJvm.runtimeClasspath(),
                    LedgerApplication.class.getName());
            launcher.environment().put("HOST", "127.0.0.1");
            launcher.environment().put("PORT", "0");
            // One file for both streams, so full pipes or undrained logs can never block the child.
            launcher.redirectErrorStream(true).redirectOutput(output.toFile());
            child = launcher.start();
            String base = awaitListening(child, output);
            walkThePublicContract(child, base);
            // Windows terminates directly; Unix SIGTERM exercises the registered shutdown path. Either way
            // the deadline proves the process cannot outlive its own cleanup.
            child.destroy();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS),
                    "Application did not terminate after shutdown:\n" + Files.readString(output));
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            // Keep output if a live child can still be writing; delete it only once no writer remains.
            if (child == null || !child.isAlive()) Files.deleteIfExists(output);
        }
    }

    // Poll the captured log so a crashed or silent startup fails with the child's own output.
    private static String awaitListening(Process child, Path output) throws Exception {
        long deadline = System.nanoTime() + STARTUP_DEADLINE.toNanos();
        String captured = "";
        while (System.nanoTime() < deadline) {
            if (!child.isAlive()) {
                return fail("Application exited with status " + child.exitValue()
                        + " before reporting a listening address:\n" + captured);
            }
            try {
                captured = Files.readString(output);
            } catch (IOException beingCreated) {
                // The redirect target can be briefly unavailable before the first bytes land.
            }
            var listening = LISTENING.matcher(captured);
            if (listening.find()) return "http://" + listening.group(1) + ":" + listening.group(2);
            Thread.sleep(50);
        }
        return fail("Application did not start within " + STARTUP_DEADLINE + ":\n" + captured);
    }

    private static void walkThePublicContract(Process child, String base) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            String source = account(client, base, 100);
            String destination = account(client, base, 0);
            String command = transferBody(source, destination, "30");
            var posted = post(client, base, "/transfers", command, "application-smoke");
            assertEquals(200, posted.statusCode(), posted.body());
            assertEquals("POSTED", JsonParser.parseString(posted.body()).getAsJsonObject().get("status").getAsString());
            assertEquals("no-store", posted.headers().firstValue("Cache-Control").orElseThrow());
            var replay = post(client, base, "/transfers", command, "application-smoke");
            assertEquals(posted.body(), replay.body(), "Same-key replay must return the original outcome");
            assertEquals(400, post(client, base, "/transfers", transferBody(source, destination, "\"30\""),
                    "application-invalid").statusCode());
            assertEquals(70, balance(client, base, source));
            assertEquals(30, balance(client, base, destination));
            assertEquals(1, JsonParser.parseString(get(client, base, "/accounts/" + destination + "/entries").body())
                    .getAsJsonArray().size());
            assertTrue(child.isAlive(), "Application must remain serving after completed requests");
        }
    }

    private static String account(HttpClient client, String base, long opening) throws Exception {
        var response = post(client, base, "/accounts", "{\"initialBalance\":" + opening + "}", null);
        assertEquals(201, response.statusCode(), response.body());
        String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
        assertEquals("/accounts/" + id + "/balance", response.headers().firstValue("Location").orElseThrow());
        return id;
    }

    private static long balance(HttpClient client, String base, String id) throws Exception {
        return JsonParser.parseString(get(client, base, "/accounts/" + id + "/balance").body())
                .getAsJsonObject().get("balance").getAsLong();
    }

    // Keep the amount as raw text so the invalid case can send a string instead of an integer.
    private static String transferBody(String source, String destination, String amount) {
        return "{\"fromAccountId\":\"" + source + "\",\"toAccountId\":\"" + destination + "\",\"amount\":" + amount + "}";
    }

    private static HttpResponse<String> post(HttpClient client, String base, String path, String body, String key)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (key != null) request = request.header("Idempotency-Key", key);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, String base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
