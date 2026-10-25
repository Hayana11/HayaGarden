package xyz.lovestyle.home.canary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * P2H-3D: the native call engine's request lanes. A slow request on one lane never
 * holds up another (Reno16, 2026-10-25: one /interrupt stuck for 20 s on the shared
 * control thread, and hear 4 reached the server before its own interrupt 4).
 *
 * - lifecycle: ring, answer, hangup/decline. Nothing else ever runs here.
 * - bargeIn: interrupt at speech_start, in order, each with a short timeout.
 * - utterances: hear/discard. Each waits (bounded) for its own utterance's
 *   interrupt, so the server sees interrupt N before hear N, as from the page.
 * - cleanup: closing the recorder (joins its thread), never on the main thread.
 *
 * Pure Java for JVM tests.
 */
public final class CallLanes implements AutoCloseable {
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> new Thread(r, "elpis-call-lifecycle"));
    private final ExecutorService bargeIn = Executors.newSingleThreadExecutor(r -> new Thread(r, "elpis-call-bargein"));
    private final ExecutorService utterances = Executors.newSingleThreadExecutor(r -> new Thread(r, "elpis-call-utterance"));
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(r -> new Thread(r, "elpis-call-cleanup"));
    private final ConcurrentHashMap<Integer, CountDownLatch> interrupts = new ConcurrentHashMap<>();
    private final long orderWaitMs;

    /** orderWaitMs bounds how long hear/discard N waits for interrupt N. */
    public CallLanes(long orderWaitMs) {
        this.orderWaitMs = orderWaitMs;
    }

    public void lifecycle(Runnable request) {
        lifecycle.execute(request);
    }

    /** speech_start(seq): its interrupt, on the barge-in lane. */
    public void interrupt(int seq, Runnable request) {
        CountDownLatch done = new CountDownLatch(1);
        interrupts.put(seq, done);
        bargeIn.execute(() -> {
            try {
                request.run();
            } finally {
                done.countDown();
            }
        });
    }

    /** hear/discard for seq, after that seq's interrupt (or orderWaitMs, whichever comes first). */
    public void utterance(int seq, Runnable request) {
        utterances.execute(() -> {
            CountDownLatch done = interrupts.remove(seq);
            if (done != null) {
                try {
                    done.await(orderWaitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            request.run();
        });
    }

    public void cleanup(Runnable task) {
        cleanup.execute(task);
    }

    /** Queued cleanup still runs; requests in flight end on their own timeouts. */
    @Override
    public void close() {
        lifecycle.shutdown();
        bargeIn.shutdown();
        utterances.shutdown();
        cleanup.shutdown();
    }
}
