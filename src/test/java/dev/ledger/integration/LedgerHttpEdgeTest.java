package dev.ledger.integration;

import dev.ledger.testing.ForkedJvm;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Duration;
import java.util.stream.Stream;

/** Parent entry point isolates HTTP ownership/disconnect scenarios that could otherwise block test cleanup. */
@Timeout(45)
class LedgerHttpEdgeTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("httpEdgeScenarios")
    // Enforce the deadline in a separate process while preserving normal JUnit lifecycle behavior inside it.
    void httpFailureAndOwnershipScenariosFinishInSeparateJvm(String scenario) throws Exception {
        ForkedJvm.run(LedgerHttpEdgeScenarios.class, scenario, Duration.ofSeconds(30));
    }

    // Derive cases from annotated child methods so new failure scenarios are automatically included.
    static Stream<String> httpEdgeScenarios() {
        return ForkedJvm.testMethods(LedgerHttpEdgeScenarios.class);
    }
}
