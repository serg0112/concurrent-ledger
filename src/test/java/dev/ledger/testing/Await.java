package dev.ledger.testing;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded latch waits shared by coordination-heavy tests; callers supply their own failure context. */
public final class Await {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private Await() {}

    /** Waits with the shared default bound so every gate fails within one predictable window. */
    public static void latch(CountDownLatch latch, String message) {
        latch(latch, DEFAULT_TIMEOUT, message);
    }

    /** Waits up to the given bound and preserves interruption before surfacing the failure. */
    public static void latch(CountDownLatch latch, Duration timeout, String message) {
        try {
            assertTrue(latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS), message);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, error);
        }
    }
}
