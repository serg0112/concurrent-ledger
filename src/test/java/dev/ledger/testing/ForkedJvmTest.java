package dev.ledger.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Confirms the parent harness can launch one selected child method through JUnit's normal lifecycle. */
@Timeout(20)
class ForkedJvmTest {
    @Test
    void runsSelectedTestWithJunitSetup() throws Exception {
        ForkedJvm.run(LifecycleFixture.class, "setupRunsBeforeTest", Duration.ofSeconds(10));
    }

    @ChildJvmOnly
    public static class LifecycleFixture {
        private boolean setupRan;

        @BeforeEach
        // Mark setup execution so invoking a method directly instead of using JUnit would fail the fixture.
        void setUp() { setupRan = true; }

        @Test
        // This passes only if JUnit invoked setup first.
        void setupRunsBeforeTest() { assertTrue(setupRan); }
    }
}
