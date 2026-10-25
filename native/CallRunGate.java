package xyz.lovestyle.home.canary;

/**
 * P2H-3C: the native call engine's lifecycle latch. One call run at a time, each
 * with its own generation; every command and async callback names the generation
 * it belongs to, so a repeated tap is a no-op and an old call's late callback
 * never acts on the next call.
 *
 *   IDLE → STARTING → (RINGING → ANSWERING →) ACTIVE → ENDING → FINISHED
 *
 * STARTING is the server ring (and, for her dial, the server answer) before
 * Telecom carries the call. Any live phase can go to ENDING; any phase of the
 * current run can be FINISHED. A new run starts only from IDLE or FINISHED.
 * Pure Java for JVM tests; all methods are synchronized.
 */
public final class CallRunGate {
    public enum Phase { IDLE, STARTING, RINGING, ANSWERING, ACTIVE, ENDING, FINISHED }

    private Phase phase = Phase.IDLE;
    private int generation;

    /** A new run, or 0 while one is starting, ringing, active or ending. */
    public synchronized int tryStart() {
        if (phase != Phase.IDLE && phase != Phase.FINISHED) return 0;
        generation++;
        phase = Phase.STARTING;
        return generation;
    }

    /** The run is this generation and not yet finished. */
    public synchronized boolean isCurrent(int gen) {
        return gen == generation && phase != Phase.IDLE && phase != Phase.FINISHED;
    }

    /** STARTING → RINGING: an incoming call is with Telecom, waiting for her. */
    public synchronized boolean toRinging(int gen) {
        return move(gen, Phase.STARTING, Phase.RINGING);
    }

    /** RINGING → ANSWERING: true once; a second Answer while the first is in flight is a no-op. */
    public synchronized boolean tryAnswer(int gen) {
        return move(gen, Phase.RINGING, Phase.ANSWERING);
    }

    /** STARTING (her dial) or ANSWERING → ACTIVE. */
    public synchronized boolean toActive(int gen) {
        return move(gen, Phase.STARTING, Phase.ACTIVE) || move(gen, Phase.ANSWERING, Phase.ACTIVE);
    }

    /** Any live phase → ENDING: true once; a second Hangup (or a late remote end) is a no-op. */
    public synchronized boolean tryEnd(int gen) {
        if (!isCurrent(gen) || phase == Phase.ENDING) return false;
        phase = Phase.ENDING;
        return true;
    }

    /** → FINISHED, once, and only for the run that is current. */
    public synchronized boolean finish(int gen) {
        if (!isCurrent(gen)) return false;
        phase = Phase.FINISHED;
        return true;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized int generation() {
        return generation;
    }

    /** The phase if gen is the current run, else null. */
    public synchronized Phase phaseOf(int gen) {
        return gen == generation ? phase : null;
    }

    private boolean move(int gen, Phase from, Phase to) {
        if (gen != generation || phase != from) return false;
        phase = to;
        return true;
    }
}
