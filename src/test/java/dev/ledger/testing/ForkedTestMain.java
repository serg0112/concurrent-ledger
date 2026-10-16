package dev.ledger.testing;

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

/** Runs one selected test with JUnit's normal assertions, extensions and lifecycle callbacks. */
public final class ForkedTestMain {
    private ForkedTestMain() {}

    /** Executes exactly one selector through JUnit and returns a failing process status for any incomplete result. */
    public static void main(String[] args) {
        // Refuse accidental direct invocation without the explicit child marker and a single method selector.
        if (args.length != 1 || !Boolean.getBoolean(ForkedJvm.CHILD_PROPERTY)) {
            throw new IllegalArgumentException("Launch through ForkedJvm with one JUnit method selector");
        }
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectMethod(args[0])).build();
        // JUnit owns setup, assertions and cleanup; summaries retain failures from every part of that lifecycle.
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        var output = new PrintWriter(System.out, true);
        summary.printTo(output);
        summary.printFailuresTo(output);
        output.flush();
        // An empty, skipped or aborted selection must not look like a successful concurrency regression.
        if (summary.getTestsFoundCount() != 1 || summary.getTestsSucceededCount() != 1
                || summary.getTotalFailureCount() != 0) {
            System.err.println("Expected exactly one successful test; empty, skipped or aborted selections fail");
            System.exit(1);
        }
        // Let the JVM exit naturally so leaked non-daemon threads still trigger the parent's deadline.
    }
}
