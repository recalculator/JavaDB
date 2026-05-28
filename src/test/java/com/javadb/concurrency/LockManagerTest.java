package com.javadb.concurrency;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LockManagerTest {

    @Test
    void multipleReadersAllowed() throws Exception {
        LockManager lm = new LockManager();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    lm.acquireReadLock("t");
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    Thread.sleep(50);
                    concurrent.decrementAndGet();
                    lm.releaseReadLock("t");
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertTrue(maxConcurrent.get() > 1, "Expected concurrent readers, got max=" + maxConcurrent.get());
    }

    @Test
    void writeLockExclusive() throws Exception {
        LockManager lm = new LockManager();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    lm.acquireWriteLock("t");
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    Thread.sleep(50);
                    concurrent.decrementAndGet();
                    lm.releaseWriteLock("t");
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await();
        assertEquals(1, maxConcurrent.get(), "Writers should be exclusive");
    }
}
