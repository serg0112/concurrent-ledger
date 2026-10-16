package dev.ledger.controller.validation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.ledger.controller.dto.CreateAccountRequest;
import dev.ledger.controller.dto.TransferRequest;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.UnsupportedMediaTypeResponse;
import jakarta.validation.Validator;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict JSON decoding followed by Jakarta field validation; business rules stay in the ledger.
 */
public final class LedgerRequestReader {
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern ACCOUNT_ID = Pattern.compile(TransferRequest.ACCOUNT_ID_PATTERN);

    private final Gson json;
    private final Validator validator;

    public LedgerRequestReader(Gson json, Validator validator) {
        this.json = Objects.requireNonNull(json);
        this.validator = Objects.requireNonNull(validator);
    }

    private static String readString(JsonObject body, String field) {
        if (!body.has(field) || body.get(field).isJsonNull()) return null;
        JsonElement element = body.get(field);
        if (element instanceof JsonPrimitive value && value.isString()) return value.getAsString();
        throw new BadRequestResponse(field + " must be a string");
    }

    private static Long readLong(JsonObject body, String field) {
        if (!body.has(field) || body.get(field).isJsonNull()) return null;
        JsonElement element = body.get(field);
        if (!(element instanceof JsonPrimitive value) || !value.isNumber()
                || !INTEGER.matcher(value.getAsString()).matches()) {
            throw new BadRequestResponse(field + " must be an integer JSON number in minor units");
        }
        String token = value.getAsString();
        try {
            // Parse from the original decimal text to reject overflow without losing large-integer precision.
            return Long.parseLong(token);
        } catch (NumberFormatException _) {
            throw new BadRequestResponse(field + " exceeds signed 64-bit range");
        }
    }

    public static UUID parseAccountId(String value) {
        if (!ACCOUNT_ID.matcher(value).matches()) {
            throw new BadRequestResponse("Invalid account UUID");
        }
        return UUID.fromString(value);
    }

    public long readInitialBalance(Context ctx) {
        return readCreateAccount(readBody(ctx)).initialBalance();
    }

    CreateAccountRequest readCreateAccount(String content) {
        var body = parseJsonObject(content, Set.of("initialBalance"));
        Long initialBalance = body.has("initialBalance") ? readLong(body, "initialBalance") : Long.valueOf(0);
        return validate(new CreateAccountRequest(initialBalance));
    }

    public TransferRequest readTransfer(Context ctx) {
        var keys = Collections.list(ctx.req().getHeaders("Idempotency-Key"));
        if (keys.size() != 1) {
            throw new BadRequestResponse("Exactly one Idempotency-Key header is required");
        }
        return readTransfer(readBody(ctx), keys.getFirst());
    }

    TransferRequest readTransfer(String content, String key) {
        var body = parseJsonObject(content, Set.of("fromAccountId", "toAccountId", "amount"));
        return validate(new TransferRequest(key, readString(body, "fromAccountId"),
                readString(body, "toAccountId"), readLong(body, "amount")));
    }

    // Javalin does not run these annotations automatically; choose a stable field order for the first error.
    private <T> T validate(T request) {
        validator.validate(request).stream()
                .min(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .ifPresent(violation -> {
                    throw new BadRequestResponse(violation.getMessage());
                });
        return request;
    }

    private String readBody(Context ctx) {
        String contentType = ctx.contentType();
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new UnsupportedMediaTypeResponse("Content-Type must be application/json");
        }
        return new String(ctx.bodyAsBytes(), StandardCharsets.UTF_8);
    }

    JsonObject parseJsonObject(String body, Set<String> allowed) {
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            // Reject lenient JSON syntax and require one flat object, matching the two request DTO schemas.
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new BadRequestResponse("JSON object required");
            }
            JsonObject object = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                // Inspect decoded names before a JSON tree can silently replace an earlier value.
                if (object.has(name)) {
                    throw new BadRequestResponse("Duplicate JSON field: " + name);
                }
                if (!allowed.contains(name)) {
                    throw new BadRequestResponse("Unknown JSON field");
                }
                // Annotations validate fields, but the transport schema must reject nested objects and arrays first.
                if (reader.peek() == JsonToken.BEGIN_OBJECT || reader.peek() == JsonToken.BEGIN_ARRAY) {
                    throw new BadRequestResponse("JSON fields must contain scalar values");
                }
                object.add(name, json.fromJson(reader, JsonElement.class));
            }
            reader.endObject();
            // A valid first object must not mask a second document or trailing garbage.
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new BadRequestResponse("Malformed JSON");
            }
            return object;
        // Provider failures occur outside this catch and remain HTTP 500.
        } catch (JsonParseException | IOException | IllegalStateException _) {
            throw new BadRequestResponse("Malformed JSON");
        }
    }
}
