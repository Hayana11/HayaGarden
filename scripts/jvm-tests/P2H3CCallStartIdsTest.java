package xyz.lovestyle.home.canary;

/**
 * P2H-3C: an ended call stops the service only with its own start requests.
 * FakeService models Service.stopSelf(int): it stops only when the id is the
 * most recent start request.
 */
public final class P2H3CCallStartIdsTest {
    static final class FakeService {
        int latest;
        boolean running;

        int start() {
            running = true;
            return ++latest;
        }

        void stopSelf(int startId) {
            if (startId == latest) running = false;
        }
    }

    public static void main(String[] args) {
        redialKeepsTheNewCall();
        endedCallLeavesNoIdleService();
        refusedTapIsCoveredByTheCall();
        System.out.println("P2H-3C call start ids JVM tests passed");
    }

    /** Old call finishing after the redial's start request: the redial's id is not the old call's. */
    private static void redialKeepsTheNewCall() {
        FakeService service = new FakeService();
        CallStartIds old = new CallStartIds(service.start());  // dial
        old.own(service.start());                              // answer tap
        old.own(service.start());                              // hangup tap
        int redial = service.start();                          // new dial, new run
        CallStartIds fresh = new CallStartIds(redial);
        service.stopSelf(old.stopId());
        check(service.running, "the old call's finish stopped the redialed call");
        check(fresh.stopId() == redial && old.stopId() < redial, "ids stay with their own call");
        service.stopSelf(fresh.stopId());
        check(!service.running, "the new call's own finish stops the service");
    }

    /** Answer/Hangup/route taps are newer than the dial: owning them lets the finish really stop. */
    private static void endedCallLeavesNoIdleService() {
        FakeService service = new FakeService();
        CallStartIds call = new CallStartIds(service.start());
        call.own(service.start());  // speaker
        call.own(service.start());  // hangup
        service.stopSelf(call.stopId());
        check(!service.running, "the service lingered after the call");
    }

    /** A second dial refused while the call runs is owned by that call. */
    private static void refusedTapIsCoveredByTheCall() {
        FakeService service = new FakeService();
        CallStartIds call = new CallStartIds(service.start());
        call.own(service.start());  // refused double dial
        call.own(1);                // an older id never lowers it
        service.stopSelf(call.stopId());
        check(!service.running, "a refused tap kept the service alive");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
