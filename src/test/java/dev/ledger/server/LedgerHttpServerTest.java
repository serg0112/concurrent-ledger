package dev.ledger.server;

import dev.ledger.service.LedgerService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ledger.storage.memory.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies wire contracts with real listeners: status codes, replay bytes and numeric exactness. */
@Timeout(45)
class LedgerHttpServerTest {
    private LedgerHttpServer server;
    private HttpClient client;
    private String base;

    // Give every test fresh state and an OS-selected loopback port to avoid sharing balances or keys.
    @BeforeEach void start() throws Exception {
        server = new LedgerHttpServer(new LedgerService(new InMemoryStorage()), new InetSocketAddress("127.0.0.1", 0));
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    // Close client activity before stopping the server so completed tests do not retain connections or threads.
    @AfterEach void close() { client.close(); server.close(); }

    @Test void accountTransferReplayConflictAndJournalOverHttp() throws Exception {
        String a = account(100), b = account(0);
        String command = command(a, b, "25");
        HttpResponse<String> original = post("/transfers", command, "http-payment");
        assertEquals(200, original.statusCode());
        assertEquals("POSTED", json(original).get("status").getAsString());
        HttpResponse<String> replay = post("/transfers", command, "http-payment");
        assertEquals(original.statusCode(), replay.statusCode());
        assertEquals(original.body(), replay.body());
        assertEquals(409, post("/transfers", command(a, b, "26"), "http-payment").statusCode());
        assertEquals(75, json(get("/accounts/" + a + "/balance")).get("balance").getAsLong());
        assertEquals(25, json(get("/accounts/" + b + "/balance")).get("balance").getAsLong());
        assertEquals(2, JsonParser.parseString(get("/accounts/" + a + "/entries").body()).getAsJsonArray().size());
    }

    @Test void rejectsMalformedInputAndUsesExplicitHttpStatuses() throws Exception {
        String a = account(10), b = account(0);
        assertEquals(400, post("/accounts", "{", null).statusCode());
        assertEquals(400, post("/accounts", "null", null).statusCode());
        assertEquals(400, post("/accounts", "{\"initialBalance\":-1}", null).statusCode());
        assertEquals(400, post("/accounts", "{\"initialBalance\":1.1}", null).statusCode());
        assertEquals(400, post("/accounts", "{\"initialBalance\":\"10\"}", null).statusCode());
        assertEquals(400, post("/accounts", "{\"initialBalance\":9223372036854775808}", null).statusCode());
        assertEquals(400, post("/accounts", "{\"typo\":1}", null).statusCode());
        assertEquals(413, post("/accounts", " ".repeat(8193), null).statusCode());
        assertEquals(400, post("/transfers", command(a, b, "1"), null).statusCode());
        assertEquals(400, post("/transfers", command(a, b, "1"), "invalid key").statusCode());
        assertEquals(400, post("/transfers", command(a, b, "1.0"), "decimal").statusCode());
        assertEquals(400, post("/transfers", command(a, b, "1e0"), "exponent").statusCode());
        assertEquals(400, post("/transfers", command(a, b, "0"), "zero").statusCode());
        assertEquals(422, post("/transfers", command(a, b, "11"), "insufficient").statusCode());
        assertEquals(404, post("/transfers", command(a, UUID.randomUUID().toString(), "1"), "missing").statusCode());
        assertEquals(404, get("/accounts/" + UUID.randomUUID() + "/balance").statusCode());
        assertEquals(400, get("/accounts/1-1-1-1-1/balance").statusCode());
        HttpResponse<String> method = get("/accounts");
        assertEquals(405, method.statusCode());
        assertEquals("POST", method.headers().firstValue("Allow").orElseThrow());
        assertEquals(404, get("/unknown").statusCode());
        HttpRequest plain = HttpRequest.newBuilder(URI.create(base + "/accounts")).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(415, client.send(plain, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void omittedOpeningBalanceIsZeroAndExplicitNullIsRejected() throws Exception {
        var created = post("/accounts", "{}", null);
        assertEquals(201, created.statusCode());
        assertEquals(0, json(created).get("balance").getAsLong());
        assertEquals(400, post("/accounts", "{\"initialBalance\":null}", null).statusCode());
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-store", get("/unknown").headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings = {"null-source", "invalid-source", "missing-amount"})
    // Malformed DTO fields must fail before key ownership, allowing a corrected command to use the same key.
    void invalidDtoDoesNotReserveKeyAndCorrectedRequestCanUseIt(String invalidField) throws Exception {
        String a = account(10), b = account(0);
        var body = JsonParser.parseString(command(a, b, "1")).getAsJsonObject();
        // Vary one field per case while keeping the rest valid, so each failure has an unambiguous cause.
        switch (invalidField) {
            case "null-source" -> body.add("fromAccountId", com.google.gson.JsonNull.INSTANCE);
            case "invalid-source" -> body.addProperty("fromAccountId", "1-1-1-1-1");
            case "missing-amount" -> body.remove("amount");
            default -> throw new AssertionError(invalidField);
        }
        var rejected = post("/transfers", body.toString(), "dto-key");
        assertEquals(400, rejected.statusCode());
        assertTrue(json(rejected).has("error"));
        var corrected = post("/transfers", command(a, b, "2"), "dto-key");
        assertEquals(200, corrected.statusCode());
        assertEquals(corrected.body(), post("/transfers", command(a, b, "2"), "dto-key").body());
        assertEquals(8, json(get("/accounts/" + a + "/balance")).get("balance").getAsLong());
        assertEquals(2, json(get("/accounts/" + b + "/balance")).get("balance").getAsLong());
    }

    @ParameterizedTest @ValueSource(strings = {"escapedAmount"})
    // Detect duplicate decoded names before a JSON tree can erase evidence, then reuse the untouched key.
    void duplicateFieldsRejectBeforeKeyReservation(String field) throws Exception {
        String a = account(100), b = account(0);
        String valid = command(a, b, "7");
        String duplicateName = field.equals("escapedAmount") ? "am\\u006funt" : field;
        String duplicateValue = field.endsWith("AccountId") ? "\"" + a + "\"" : "1";
        String duplicate = valid.substring(0, valid.length() - 1) + ",\"" + duplicateName + "\":" + duplicateValue + "}";
        var rejected = post("/transfers", duplicate, "duplicate-field");
        assertEquals(400, rejected.statusCode());
        assertTrue(json(rejected).get("error").getAsString().startsWith("Duplicate JSON field:"));
        assertEquals(100, json(get("/accounts/" + a + "/balance")).get("balance").getAsLong());
        assertEquals(0, JsonParser.parseString(get("/accounts/" + b + "/entries").body()).getAsJsonArray().size());
        var corrected = post("/transfers", valid, "duplicate-field");
        assertEquals(200, corrected.statusCode());
        assertEquals(93, json(get("/accounts/" + a + "/balance")).get("balance").getAsLong());
        assertEquals(7, json(get("/accounts/" + b + "/balance")).get("balance").getAsLong());
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"initialBalance\":1,\"initialBalance\":7}", "{} {}", "{\"initialBalance\":[]}"
    })
    void streamingValidationRejectsDuplicateFundingAndInvalidJson(String body) throws Exception {
        assertEquals(400, post("/accounts", body, null).statusCode());
    }

    // Assert raw response digits as well as parsed longs so a floating-point conversion cannot go unnoticed.
    @Test void largeAmountsRemainExactOnTheWire() throws Exception {
        String a = account(9_007_199_254_740_993L), b = account(0);
        var result = post("/transfers", command(a, b, "9007199254740993"), "large-exact");
        assertEquals(200, result.statusCode());
        assertEquals(9_007_199_254_740_993L, json(result).getAsJsonObject("transfer").get("amount").getAsLong());
        var destination = get("/accounts/" + b + "/balance");
        assertTrue(destination.body().contains("\"balance\":9007199254740993"));
        assertEquals(9_007_199_254_740_993L, json(destination).get("balance").getAsLong());
    }

    private String account(long amount) throws Exception {
        HttpResponse<String> response = post("/accounts", "{\"initialBalance\":" + amount + "}", null);
        assertEquals(201, response.statusCode());
        return json(response).get("id").getAsString();
    }
    // Keep the amount as raw text so tests can deliberately send invalid JSON-number forms.
    private static String command(String a, String b, String amount) {
        return "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amount\":" + amount + "}";
    }
    private HttpRequest request(String path, String body, String key) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        return builder.build();
    }
    private HttpResponse<String> post(String path, String body, String key) throws Exception {
        return client.send(request(path, body, key), HttpResponse.BodyHandlers.ofString());
    }
    // Bound read requests so a missing response fails within the test's outer process deadline.
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonObject json(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
}
