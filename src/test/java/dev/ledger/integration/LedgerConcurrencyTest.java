package dev.ledger.integration;

import dev.ledger.testing.ForkedJvm;
import dev.ledger.testing.DeadlockFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Duration;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Parent entry point for isolated ledger races, including verification that the watchdog can kill a real deadlock. */
@Timeout(45)
class LedgerConcurrencyTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("concurrencyScenarios")
    // The parent owns the process timeout, so even noninterruptible worker cleanup cannot trap this test JVM.
    void concurrencyScenarioCompletesInSeparateJvm(String scenario) throws Exception {
        ForkedJvm.run(LedgerConcurrencyScenarios.class, scenario, Duration.ofSeconds(30));
    }

    // Discover annotated scenarios instead of maintaining a second list that could omit a newly added test.
    static Stream<String> concurrencyScenarios() {
        return ForkedJvm.testMethods(LedgerConcurrencyScenarios.class);
    }

    // Require proof of deadlock and confirmed termination so a failed child cannot be mistaken for watchdog success.
    @Test void deadlineTerminatesAnActualDeadlockDespiteBlockedExecutorClose() {
        // The child confirms its deterministic deadlock in well under a second, so 2s is ample headroom for a cold
        // start while still forcing the watchdog to kill a process that cannot exit on its own.
        AssertionError error = assertThrows(AssertionError.class, () ->
                ForkedJvm.run(DeadlockFixture.class, "executorCloseCannotFinish", Duration.ofSeconds(2)));
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        assertTrue(error.getMessage().contains("terminated=true"), error.getMessage());
        assertTrue(error.getMessage().contains("DEADLOCK_CONFIRMED"), error.getMessage());
    }
}
