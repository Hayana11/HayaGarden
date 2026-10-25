package xyz.lovestyle.home.canary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** P2H-3D: a stuck barge-in never delays the call's lifecycle; interrupt N precedes hear N. */
public final class P2H3DCallLanesTest {
    public static void main(String[] args) throws Exception {
        stuckInterruptDoesNotDelayLifecycle();
        hearWaitsForItsOwnInterrupt();
        stuckInterruptDelaysHearOnlyBoundedly();
        utterancesKeepTheirOrder();
        System.out.println("P2H-3D call lanes JVM tests passed");
    }

    /** Reno16 2026-10-25: one /interrupt held the shared control thread for 20 s. */
    private static void stuckInterruptDoesNotDelayLifecycle() throws Exception {
        try (CallLanes lanes = new CallLanes(6000)) {
            CountDownLatch stuck = new CountDownLatch(1);
            lanes.interrupt(3, () -> await(stuck, 30_000));
            for (String step : new String[] {"answer", "hangup"}) {
                CountDownLatch ran = new CountDownLatch(1);
                long started = System.nanoTime();
                lanes.lifecycle(ran::countDown);
                check(ran.await(1, TimeUnit.SECONDS), step + " waited behind a stuck interrupt");
                check(millisSince(started) < 500, step + " delayed by " + millisSince(started) + " ms");
            }
            stuck.countDown();
        }
    }

    private static void hearWaitsForItsOwnInterrupt() throws Exception {
        try (CallLanes lanes = new CallLanes(6000)) {
            List<String> server = new CopyOnWriteArrayList<>();
            CountDownLatch slow = new CountDownLatch(1);
            lanes.interrupt(4, () -> {
                await(slow, 5000);
                server.add("interrupt 4");
            });
            CountDownLatch heard = new CountDownLatch(1);
            lanes.utterance(4, () -> {
                server.add("hear 4");
                heard.countDown();
            });
            Thread.sleep(200);
            check(server.isEmpty(), "hear 4 overtook interrupt 4");
            slow.countDown();
            check(heard.await(2, TimeUnit.SECONDS), "hear 4 never sent");
            check(server.equals(List.of("interrupt 4", "hear 4")), "order: " + server);
        }
    }

    /** A server that never answers the interrupt delays hear by orderWaitMs, not forever. */
    private static void stuckInterruptDelaysHearOnlyBoundedly() throws Exception {
        try (CallLanes lanes = new CallLanes(300)) {
            CountDownLatch never = new CountDownLatch(1);
            lanes.interrupt(5, () -> await(never, 30_000));
            CountDownLatch heard = new CountDownLatch(1);
            long started = System.nanoTime();
            lanes.utterance(5, heard::countDown);
            check(heard.await(2, TimeUnit.SECONDS), "hear blocked by a stuck interrupt");
            long waited = millisSince(started);
            check(waited >= 250 && waited < 1500, "bounded wait, was " + waited + " ms");
            never.countDown();
        }
    }

    /** No interrupt seen for a seq (e.g. a discard): it goes at once, still in submit order. */
    private static void utterancesKeepTheirOrder() throws Exception {
        try (CallLanes lanes = new CallLanes(6000)) {
            List<Integer> sent = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);
            for (int seq = 6; seq <= 8; seq++) {
                final int n = seq;
                lanes.utterance(n, () -> {
                    sent.add(n);
                    done.countDown();
                });
            }
            check(done.await(2, TimeUnit.SECONDS), "utterances not sent");
            check(sent.equals(List.of(6, 7, 8)), "order: " + sent);
        }
    }

    private static void await(CountDownLatch latch, long ms) {
        try {
            latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long millisSince(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
