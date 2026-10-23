package xyz.lovestyle.home.canary;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain-JVM contract for CallState (run by scripts/test-p2g-call-notification.sh). */
public final class CallStateTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        triState();
        malformedIdChangesNothing();
        bothPollersShareOneKnownCall();
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("CallState JVM tests passed");
    }

    static void triState() {
        CallState.resetForTest();
        check(CallState.apply(false, null) == CallState.Action.NONE && CallState.known() == null,
                "call omitted: nothing changes");
        check(CallState.apply(true, "abc123") == CallState.Action.SHOW && "abc123".equals(CallState.known()),
                "call object: show it and remember it as known_call");
        check(CallState.apply(false, null) == CallState.Action.NONE && "abc123".equals(CallState.known()),
                "call omitted again: the notification is left alone");
        check(CallState.apply(true, "def456") == CallState.Action.SHOW && "def456".equals(CallState.known()),
                "a different call replaces the known one (same fixed notification)");
        check(CallState.apply(true, null) == CallState.Action.CANCEL && CallState.known() == null,
                "call null: withdraw it and forget it");
        check(CallState.apply(true, null) == CallState.Action.CANCEL,
                "a repeated withdrawal is a harmless cancel");
    }

    static void malformedIdChangesNothing() {
        CallState.resetForTest();
        CallState.apply(true, "abc123");
        for (String bad : new String[] {"", "x'; --", "a/b", "0123456789012345678901234567890123456789012345678901234567890123456789"}) {
            check(CallState.apply(true, bad) == CallState.Action.NONE && "abc123".equals(CallState.known()),
                    "malformed id ignored: " + (bad.length() > 12 ? bad.substring(0, 12) + "…" : bad));
        }
    }

    /** PushService and NotificationPollWorker apply deltas concurrently; the state stays one value. */
    static void bothPollersShareOneKnownCall() throws Exception {
        for (int round = 0; round < 50; round++) {
            CallState.resetForTest();
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicInteger shows = new AtomicInteger();
            Runnable poller = () -> {
                try {
                    start.await();
                } catch (Exception e) {
                    return;
                }
                if (CallState.apply(true, "abc123") == CallState.Action.SHOW) {
                    shows.incrementAndGet();
                }
            };
            Thread push = new Thread(poller);
            Thread worker = new Thread(poller);
            push.start();
            worker.start();
            push.join();
            worker.join();
            if (!"abc123".equals(CallState.known()) || shows.get() != 2) {
                check(false, "round " + round + ": known=" + CallState.known() + " shows=" + shows.get());
                return;
            }
        }
        check(true, "two pollers, 50 rounds: one known call, both updates land on the fixed notification");
    }
}
