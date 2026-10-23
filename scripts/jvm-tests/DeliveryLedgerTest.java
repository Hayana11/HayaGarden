package xyz.lovestyle.home.canary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain-JVM contract for DeliveryLedger (run by scripts/test-p2d-push.sh). */
public final class DeliveryLedgerTest {
    /** Stands in for SharedPreferences: survives "restarts" by sharing the backing fields. */
    static final class Disk {
        volatile String shown = "";
        volatile long cursor = 0L;
    }

    static final class DiskStore implements DeliveryLedger.Store {
        final Disk disk;
        DiskStore(Disk disk) { this.disk = disk; }
        @Override public String readShownIds() { return disk.shown; }
        @Override public long readCursor() { return disk.cursor; }
        @Override public void write(String shownIds, long cursor) { disk.shown = shownIds; disk.cursor = cursor; }
    }

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        concurrentWorkerAndServiceShowOnce();
        repeatedIdShownOnce();
        restartRemembersOldIds();
        notShownIsNotRecorded();
        boundedListAndMonotonicCursor();
        if (failures > 0) {
            System.out.println("DeliveryLedger contract FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("DeliveryLedger contract PASSED");
    }

    static void concurrentWorkerAndServiceShowOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            Disk disk = new Disk();
            AtomicInteger shown = new AtomicInteger();
            int threads = 8;
            CyclicBarrier start = new CyclicBarrier(threads);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger alreadyShown = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    try {
                        start.await();
                        DeliveryLedger.Result r = DeliveryLedger.deliver(new DiskStore(disk), "wake-7", () -> {
                            shown.incrementAndGet();
                            try { Thread.sleep(2); } catch (InterruptedException ignored) { }
                            return true;
                        });
                        if (r == DeliveryLedger.Result.ALREADY_SHOWN) alreadyShown.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            done.await();
            if (shown.get() != 1 || alreadyShown.get() != threads - 1) {
                check(false, "round " + round + ": shown " + shown.get() + " times");
                return;
            }
        }
        check(true, "worker and PushService racing on one id show it exactly once (50 rounds x 8 threads)");
    }

    static void repeatedIdShownOnce() {
        Disk disk = new Disk();
        AtomicInteger shown = new AtomicInteger();
        DeliveryLedger.Result first = DeliveryLedger.deliver(new DiskStore(disk), "wake-3", () -> shown.incrementAndGet() > 0);
        DeliveryLedger.Result second = DeliveryLedger.deliver(new DiskStore(disk), "wake-3", () -> shown.incrementAndGet() > 0);
        DeliveryLedger.Result third = DeliveryLedger.deliver(new DiskStore(disk), "wake-3", () -> shown.incrementAndGet() > 0);
        check(first == DeliveryLedger.Result.SHOWN && second == DeliveryLedger.Result.ALREADY_SHOWN
                && third == DeliveryLedger.Result.ALREADY_SHOWN && shown.get() == 1,
                "the same id received repeatedly is shown once");
    }

    static void restartRemembersOldIds() {
        Disk disk = new Disk();
        DeliveryLedger.deliver(new DiskStore(disk), "wake-11", () -> true);
        // App process restarts: a brand-new store object reading the same persisted data.
        AtomicInteger shownAfterRestart = new AtomicInteger();
        DeliveryLedger.Result r = DeliveryLedger.deliver(new DiskStore(disk), "wake-11",
                () -> shownAfterRestart.incrementAndGet() > 0);
        check(r == DeliveryLedger.Result.ALREADY_SHOWN && shownAfterRestart.get() == 0 && disk.cursor == 11L,
                "after an app restart an old id is not shown again and the cursor survives");
    }

    static void notShownIsNotRecorded() {
        Disk disk = new Disk();
        DeliveryLedger.Result denied = DeliveryLedger.deliver(new DiskStore(disk), "wake-5", () -> false);
        DeliveryLedger.Result later = DeliveryLedger.deliver(new DiskStore(disk), "wake-5", () -> true);
        check(denied == DeliveryLedger.Result.NOT_SHOWN && later == DeliveryLedger.Result.SHOWN && disk.cursor == 5L,
                "without notification permission nothing is recorded, so it is shown once permission returns");
    }

    static void boundedListAndMonotonicCursor() {
        Disk disk = new Disk();
        for (int i = 1; i <= DeliveryLedger.KEPT + 10; i++) {
            final int n = i;
            DeliveryLedger.deliver(new DiskStore(disk), "wake-" + n, () -> true);
        }
        DeliveryLedger.deliver(new DiskStore(disk), "wake-2", () -> true);
        int kept = disk.shown.split("\n").length;
        check(kept == DeliveryLedger.KEPT && disk.cursor == DeliveryLedger.KEPT + 10L,
                "ledger keeps the newest " + DeliveryLedger.KEPT + " ids and the cursor never moves backwards");
    }
}
