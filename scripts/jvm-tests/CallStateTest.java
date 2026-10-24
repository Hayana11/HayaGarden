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
        staleResponseCannotReshowWithdrawnCall();
        staleExpectedNullHasNoAba();
        staleShowCannotOverrideNewerCall();
        withdrawalAdvancesTheFence();
        nullSnapshotChangesNothing();
        bothPollersShareOneKnownCall();
        ringCapEndsOnlyItsOwnRing();
        stoppingEndsTheRingAndDisarmsItsTimer();
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("CallState JVM tests passed");
    }

    static String known() {
        return CallState.snapshot().known;
    }

    /** Sequential polls, each sent with a fresh snapshot. */
    static CallState.Action poll(boolean present, String id) {
        return CallState.apply(CallState.snapshot(), present, id);
    }

    static void triState() {
        CallState.resetForTest();
        check(poll(false, null) == CallState.Action.NONE && known() == null,
                "call omitted: nothing changes");
        check(poll(true, "abc123") == CallState.Action.SHOW && "abc123".equals(known()),
                "call object: show it and remember it as known_call");
        check(poll(false, null) == CallState.Action.NONE && "abc123".equals(known()),
                "call omitted again: the notification is left alone");
        check(poll(true, "def456") == CallState.Action.SHOW && "def456".equals(known()),
                "a different call replaces the known one (same fixed notification)");
        check(poll(true, null) == CallState.Action.CANCEL && known() == null,
                "call null: withdraw it and forget it");
        check(poll(true, null) == CallState.Action.CANCEL,
                "a repeated withdrawal is a harmless cancel");
    }

    static void malformedIdChangesNothing() {
        CallState.resetForTest();
        poll(true, "abc123");
        for (String bad : new String[] {"", "x'; --", "a/b", "0123456789012345678901234567890123456789012345678901234567890123456789"}) {
            CallState.Snapshot before = CallState.snapshot();
            check(CallState.apply(before, true, bad) == CallState.Action.NONE && "abc123".equals(known())
                            && CallState.apply(before, true, "abc123") == CallState.Action.SHOW,
                    "malformed id ignored without bumping the revision: "
                            + (bad.length() > 12 ? bad.substring(0, 12) + "…" : bad));
        }
    }

    /**
     * The reviewed race: both pollers send at rev0/known=null, response 1 shows
     * A, a newer request withdraws it, then the delayed response 2 (rev0, A)
     * arrives and must change nothing.
     */
    static void staleResponseCannotReshowWithdrawnCall() {
        CallState.resetForTest();
        CallState.Snapshot push = CallState.snapshot();
        CallState.Snapshot worker = CallState.snapshot();
        check(push.known == null && worker.known == null, "both requests go out with known_call omitted");
        check(CallState.apply(push, true, "A1") == CallState.Action.SHOW && "A1".equals(known()),
                "response 1 shows A (rev1)");
        CallState.Snapshot next = CallState.snapshot();
        check("A1".equals(next.known), "the next request sends known_call=A");
        check(CallState.apply(next, true, null) == CallState.Action.CANCEL && known() == null,
                "its call: null withdraws A (rev2)");
        check(CallState.apply(worker, true, "A1") == CallState.Action.NONE && known() == null,
                "delayed response 2 (rev0, A) is stale: NONE, known stays null");
        check(poll(false, null) == CallState.Action.NONE && known() == null,
                "nothing later resurrects A");
    }

    /** null -> A -> null: a response sent at the first null must not pass just because known is null again. */
    static void staleExpectedNullHasNoAba() {
        CallState.resetForTest();
        CallState.Snapshot old = CallState.snapshot();
        poll(true, "A1");
        poll(true, null);
        check(known() == null, "known is null again");
        check(CallState.apply(old, true, "A1") == CallState.Action.NONE && known() == null,
                "old expected-null response is still stale (revision, not known, is the fence)");
        check(CallState.apply(old, true, null) == CallState.Action.NONE,
                "an old withdrawal is stale too");
    }

    /** A stale SHOW of an old call cannot replace the newer ringing one. */
    static void staleShowCannotOverrideNewerCall() {
        CallState.resetForTest();
        CallState.Snapshot old = CallState.snapshot();
        poll(true, "B2");
        check(CallState.apply(old, true, "A1") == CallState.Action.NONE && "B2".equals(known()),
                "stale SHOW A leaves B shown");
    }

    /** A withdrawal is a change too: a request sent while A was shown is stale after it. */
    static void withdrawalAdvancesTheFence() {
        CallState.resetForTest();
        poll(true, "A1");
        CallState.Snapshot whileShown = CallState.snapshot();
        poll(true, null);
        check(CallState.apply(whileShown, true, "B2") == CallState.Action.NONE && known() == null,
                "a response sent before the withdrawal cannot show anything after it");
    }

    static void nullSnapshotChangesNothing() {
        CallState.resetForTest();
        check(CallState.apply(null, true, "A1") == CallState.Action.NONE && known() == null,
                "a response without a request snapshot applies nothing");
    }

    /** The 65 s cap timer of an old call must never stop the next call's vibration. */
    static void ringCapEndsOnlyItsOwnRing() {
        CallState.resetForTest();
        long first = CallState.beginRing();
        long second = CallState.beginRing();  // a new SHOW replaces the first ring
        check(!CallState.endRingIfCurrent(first), "the first call's cap timer is stale once a second call rings");
        check(CallState.endRingIfCurrent(second), "the second call's own cap timer stops it");
        check(!CallState.endRingIfCurrent(second), "a cap timer fires at most once");
    }

    static void stoppingEndsTheRingAndDisarmsItsTimer() {
        CallState.resetForTest();
        long token = CallState.beginRing();
        check(CallState.endRing(), "a withdrawal or tap ends the live ring");
        check(!CallState.endRingIfCurrent(token), "its cap timer then does nothing");
        long next = CallState.beginRing();
        check(!CallState.endRingIfCurrent(token), "and cannot stop the next call either");
        check(CallState.endRingIfCurrent(next), "the next call keeps its own cap");
        check(!CallState.endRing(), "stopping when nothing rings is harmless");
    }

    /**
     * PushService and NotificationPollWorker send concurrently from the same
     * snapshot and both get the same call: exactly one applies, the other is
     * stale (the call is already shown), and the state stays one value.
     */
    static void bothPollersShareOneKnownCall() throws Exception {
        for (int round = 0; round < 50; round++) {
            CallState.resetForTest();
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicInteger shows = new AtomicInteger();
            AtomicInteger stale = new AtomicInteger();
            Runnable poller = () -> {
                CallState.Snapshot at = CallState.snapshot();
                try {
                    start.await();
                } catch (Exception e) {
                    return;
                }
                CallState.Action action = CallState.apply(at, true, "abc123");
                if (action == CallState.Action.SHOW) {
                    shows.incrementAndGet();
                } else if (action == CallState.Action.NONE) {
                    stale.incrementAndGet();
                }
            };
            Thread push = new Thread(poller);
            Thread worker = new Thread(poller);
            push.start();
            worker.start();
            push.join();
            worker.join();
            if (!"abc123".equals(known()) || shows.get() != 1 || stale.get() != 1) {
                check(false, "round " + round + ": known=" + known() + " shows=" + shows.get()
                        + " stale=" + stale.get());
                return;
            }
        }
        check(true, "two pollers, 50 rounds: one known call, exactly one applies, the other is fenced");
    }
}
