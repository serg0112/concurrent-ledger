package dev.ledger.testing;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** No pipe-reader or executor shutdown can hold the parent hostage to a child deadlock. */
public final class ForkedJvm {
    public static final String CHILD_PROPERTY = "ledger.test.child";

    private ForkedJvm() {}

    /** Enumerates the ordinary, parameterless JUnit tests used by the parent parameterized test. */
    public static Stream<String> testMethods(Class<?> testClass) {
        return Arrays.stream(testClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .map(Method::getName)
                .sorted();
    }

    /** Runs one normal JUnit method in a child and enforces its deadline from outside the potentially blocked JVM. */
    public static void run(Class<?> testClass, String testMethod, Duration deadline) throws Exception {
        String selector = testClass.getName() + "#" + testMethod;
        // Redirect to a file so undrained output pipes cannot block the child or require a second waiting executor.
        Path output = Files.createTempFile("ledger-" + testMethod + "-", ".log");
        Process child = null;
        try {
            child = new ProcessBuilder(javaExecutable(), "-D" + CHILD_PROPERTY + "=true", "-cp", runtimeClasspath(),
                    ForkedTestMain.class.getName(), selector)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            // Interrupting Java lock acquisition may not help; kill the process and confirm OS-level termination.
            if (!child.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                boolean terminated = child.waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("Child JVM timed out: " + selector + "; terminated=" + terminated
                        + "\n" + Files.readString(output));
            }
            // Include captured child output so failed assertions or setup/cleanup errors remain diagnosable in the parent.
            if (child.exitValue() != 0) {
                throw new AssertionError("Child JVM failed: " + selector + "; exit=" + child.exitValue()
                        + "\n" + Files.readString(output));
            }
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
            // Keep output if termination is unconfirmed; delete it only after no live child can still be writing.
            if (child == null || !child.isAlive()) {
                Files.deleteIfExists(output);
            }
        }
    }

    /** JVM executable resolved from the current java.home so every child runs on the parent's runtime. */
    public static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    /** Full test runtime classpath; Gradle's worker java.class.path omits the test runtime dependencies. */
    public static String runtimeClasspath() {
        return System.getProperty("ledger.test.classpath", System.getProperty("java.class.path"));
    }
}
