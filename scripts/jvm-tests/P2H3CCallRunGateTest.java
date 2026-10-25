package xyz.lovestyle.home.canary;

import xyz.lovestyle.home.canary.CallRunGate.Phase;

/** P2H-3C lifecycle latch: repeated taps are no-ops, an old call never touches a new one. */
public final class P2H3CCallRunGateTest {
    public static void main(String[] args) {
        doubleDial();
        doubleAnswer();
        doubleHangup();
        redialRightAfterEnd();
        hangupWhileStarting();
        System.out.println("P2H-3C call run gate JVM tests passed");
    }

    /** The second tap while /ring is in flight is refused, so its 409 can never finish the first call. */
    private static void doubleDial() {
        CallRunGate gate = new CallRunGate();
        int first = gate.tryStart();
        check(first > 0 && gate.phase() == Phase.STARTING, "first dial starts");
        check(gate.tryStart() == 0, "second dial while starting is refused");
        check(gate.toActive(first), "first dial goes live");
        check(gate.tryStart() == 0, "dial while active is refused");
        check(gate.isCurrent(first) && gate.phase() == Phase.ACTIVE, "first call untouched");
    }

    /** Only one /answer is sent; the second tap does nothing, so no 409 can end the call. */
    private static void doubleAnswer() {
        CallRunGate gate = new CallRunGate();
        int gen = gate.tryStart();
        check(gate.toRinging(gen), "incoming call rings");
        check(gate.tryAnswer(gen), "first answer");
        check(!gate.tryAnswer(gen), "second answer while the first is in flight is a no-op");
        check(gate.toActive(gen), "server answer -> active");
        check(!gate.tryAnswer(gen), "answer after active is a no-op");
        check(gate.phase() == Phase.ACTIVE, "still active");
    }

    private static void doubleHangup() {
        CallRunGate gate = new CallRunGate();
        int gen = gate.tryStart();
        gate.toActive(gen);
        check(gate.tryEnd(gen), "first hangup");
        check(!gate.tryEnd(gen), "second hangup is a no-op");
        check(gate.finish(gen), "finish once");
        check(!gate.finish(gen), "finish again is a no-op");
        check(!gate.tryEnd(gen), "hangup after finish is a no-op");
    }

    /** Leftover callbacks of the ended call cannot answer, end, activate or finish the new one. */
    private static void redialRightAfterEnd() {
        CallRunGate gate = new CallRunGate();
        int old = gate.tryStart();
        gate.toRinging(old);
        gate.tryAnswer(old);
        gate.tryEnd(old);
        gate.finish(old);
        int fresh = gate.tryStart();
        check(fresh > 0 && fresh != old, "redial right after the end gets a new generation");
        check(!gate.isCurrent(old), "old call is not current");
        check(!gate.toActive(old), "old server-answer cannot activate the new call");
        check(!gate.tryAnswer(old) && !gate.toRinging(old), "old answer/ring is ignored");
        check(!gate.tryEnd(old), "old hangup/remote end cannot end the new call");
        check(!gate.finish(old), "old finish cannot finish the new call");
        check(gate.phaseOf(old) == null, "old generation has no phase");
        check(gate.isCurrent(fresh) && gate.phase() == Phase.STARTING, "new call untouched");
    }

    /** Hung up during /ring: the run ends at once; the ring result, when it lands, is not current. */
    private static void hangupWhileStarting() {
        CallRunGate gate = new CallRunGate();
        int gen = gate.tryStart();
        check(gate.tryEnd(gen) && gate.finish(gen), "hangup while starting ends the run");
        check(!gate.isCurrent(gen), "the late ring result is dropped (and its server call ended)");
        check(gate.tryStart() > 0, "a new dial is allowed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
