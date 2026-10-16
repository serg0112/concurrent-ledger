package dev.ledger.testing;

import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

/** Deliberately broken code exercises the watchdog, never the normal parent JVM. */
@ChildJvmOnly
public final class DeadlockFixture {
    @Test
    void executorCloseCannotFinish() throws Exception {
        ReentrantLock left = new ReentrantLock(), right = new ReentrantLock();
        CountDownLatch holdingFirstLocks = new CountDownLatch(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> deadlock(left, right, holdingFirstLocks));
            pool.submit(() -> deadlock(right, left, holdingFirstLocks));
            Await.latch(holdingFirstLocks, "Latch timed out");
            while (ManagementFactory.getThreadMXBean().findDeadlockedThreads() == null) {
                Thread.sleep(10);
            }
            // close() waits forever on noninterruptible lock acquisition, even if interrupted.
            System.out.println("DEADLOCK_CONFIRMED");
            System.out.flush();
        }
    }

    private static void deadlock(ReentrantLock first, ReentrantLock second, CountDownLatch ready) {
        first.lock();
        try {
            ready.countDown();
            Await.latch(ready, "Latch timed out");
            second.lock();
            try {
                fail("Intentional deadlock unexpectedly resolved");
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }
}
