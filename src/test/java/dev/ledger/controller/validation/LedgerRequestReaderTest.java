package dev.ledger.controller.validation;

import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises strict decoding and DTO validation directly, without listener or socket behavior. */
class LedgerRequestReaderTest {
    private static ValidatorFactory factory;
    private static LedgerRequestReader reader;

    // Use production strictness and a real validator so tests catch parser/provider integration mistakes.
    @BeforeAll static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        reader = new LedgerRequestReader(new GsonBuilder().setStrictness(Strictness.STRICT).create(), factory.getValidator());
    }

    @AfterAll static void close() { factory.close(); }

    @Test void parserPreservesExactNumbersAndDecodedStringsWithoutHttp() {
        var body = reader.parseJsonObject("{\"amount\":9007199254740993,\"name\":\"pay\\u006dent\"}", Set.of("amount", "name"));
        assertEquals("9007199254740993", body.get("amount").getAsString());
        assertEquals("payment", body.get("name").getAsString());
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"amount\":1,\"amount\":7}", "{\"amount\":null,\"am\\u006funt\":7}",
            "{\"unknown\":1}", "{\"amount\":{}}", "{} {}", "{amount:1}"
    })
    void parserRejectsAmbiguousOrInvalidObjectsWithoutHttp(String json) {
        var error = assertThrows(io.javalin.http.BadRequestResponse.class,
                () -> reader.parseJsonObject(json, Set.of("amount")));
        assertEquals(400, error.getStatus());
    }

    // Distinguish a documented default from invalid null/negative input at the decoding boundary.
    @Test void omittedOpeningBalanceDefaultsToZeroButExplicitNullIsInvalid() {
        assertEquals(0L, reader.readCreateAccount("{}").initialBalance());
        assertThrows(io.javalin.http.BadRequestResponse.class,
                () -> reader.readCreateAccount("{\"initialBalance\":null}"));
        assertThrows(io.javalin.http.BadRequestResponse.class,
                () -> reader.readCreateAccount("{\"initialBalance\":-1}"));
    }

    @ParameterizedTest @ValueSource(strings = {"\"1\"", "1.0", "1e0", "true", "9223372036854775808", "-9223372036854775809"})
    // Try values permissive binding might normalize, ensuring both endpoints reject them before the ledger.
    void rejectsCoercibleOrOutOfRangeNumbersBeforeDtoBinding(String number) {
        assertThrows(io.javalin.http.BadRequestResponse.class,
                () -> reader.readCreateAccount("{\"initialBalance\":" + number + "}"));
        assertThrows(io.javalin.http.BadRequestResponse.class,
                () -> reader.readTransfer(transfer(number), "key"));
    }

    // Keep business-invalid but representable amounts intact so idempotency remembers their rejection.
    @Test void businessInvalidAmountsStillReachTheLedgerAsExactLongs() {
        assertEquals(0L, reader.readTransfer(transfer("0"), "key").toTransfer().amount());
        assertEquals(Long.MIN_VALUE, reader.readTransfer(transfer("-9223372036854775808"), "key").toTransfer().amount());
        assertEquals(9_007_199_254_740_993L, reader.readTransfer(transfer("9007199254740993"), "key").toTransfer().amount());
    }

    private static String transfer(String amount) {
        return "{\"fromAccountId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"toAccountId\":\"00000000-0000-0000-0000-000000000002\",\"amount\":" + amount + "}";
    }
}
