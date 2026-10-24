package xyz.lovestyle.home.canary;

/**
 * The incoming call this process believes is ringing (Android-free; shown by
 * CallNotificationSupport). It is the long poll's known_call: state
 * reconciliation, never a delivery receipt, so it lives in memory only and is
 * shared by PushService and NotificationPollWorker. After a restart the phone
 * just hears about a still-ringing call again and updates the same
 * notification; nothing on disk can make it miss one.
 *
 * The two pollers' responses can arrive out of order, so every applied
 * SHOW/CANCEL bumps a revision, and a response only applies its call delta if
 * the revision is still the one snapshotted when its request was sent (the
 * same snapshot supplies its known_call). A stale response cannot re-show a
 * withdrawn call, and a revision that only ever grows has no ABA.
 */
public final class CallState {
    public enum Action { NONE, SHOW, CANCEL }

    private static final Object LOCK = new Object();
    private static String known;
    private static long revision;
    // The app-owned call vibration (CallNotificationSupport): which ring is live.
    private static long ringToken;
    private static boolean ringing;

    /** What one poll request was sent with: its known_call and the revision it may apply against. */
    public static final class Snapshot {
        public final String known;
        final long revision;

        Snapshot(String known, long revision) {
            this.known = known;
            this.revision = revision;
        }
    }

    private CallState() {}

    /** Take before sending a poll; pass the same snapshot to its response. */
    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(known, revision);
        }
    }

    /**
     * Apply one response's call delta against the snapshot its request was sent
     * with. {@code present} false: the response had no "call" (leave the
     * notification alone). {@code id} null: "call": null (withdraw it).
     * Otherwise show or update it. A malformed id, or a response whose request
     * predates the last applied change, changes nothing.
     */
    public static Action apply(Snapshot at, boolean present, String id) {
        if (!present || at == null) {
            return Action.NONE;
        }
        synchronized (LOCK) {
            if (at.revision != revision) {
                return Action.NONE;  // stale: a newer response already reconciled the call
            }
            if (id == null) {
                known = null;
                revision++;
                return Action.CANCEL;  // idempotent: cancelling nothing is harmless
            }
            if (!valid(id)) {
                return Action.NONE;
            }
            known = id;  // even if the notification cannot be shown: no hot re-announce loop
            revision++;
            return Action.SHOW;
        }
    }

    /** A new ring starts (replacing any earlier one). Returns its token for the cap timer. */
    public static long beginRing() {
        synchronized (LOCK) {
            ringToken++;
            ringing = true;
            return ringToken;
        }
    }

    /** The ring ends now (withdrawn or opened). Returns whether one was live. */
    public static boolean endRing() {
        synchronized (LOCK) {
            boolean was = ringing;
            ringing = false;
            ringToken++;
            return was;
        }
    }

    /** The cap timer of ring {@code token}: ends it only if that ring is still the live one. */
    public static boolean endRingIfCurrent(long token) {
        synchronized (LOCK) {
            if (!ringing || token != ringToken) {
                return false;
            }
            ringing = false;
            ringToken++;
            return true;
        }
    }

    static boolean valid(String id) {
        if (id.isEmpty() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }

    static void resetForTest() {
        synchronized (LOCK) {
            known = null;
            revision = 0;
            ringToken = 0;
            ringing = false;
        }
    }
}
