package xyz.lovestyle.home.canary;

/**
 * The incoming call this process believes is ringing (Android-free; shown by
 * CallNotificationSupport). It is the long poll's known_call: state
 * reconciliation, never a delivery receipt, so it lives in memory only and is
 * shared by PushService and NotificationPollWorker. After a restart the phone
 * just hears about a still-ringing call again and updates the same
 * notification; nothing on disk can make it miss one.
 */
public final class CallState {
    public enum Action { NONE, SHOW, CANCEL }

    private static final Object LOCK = new Object();
    private static String known;

    private CallState() {}

    /** The call id to send as known_call, or null. */
    public static String known() {
        synchronized (LOCK) {
            return known;
        }
    }

    /**
     * Apply one response's call delta. {@code present} false: the response had
     * no "call" (leave the notification alone). {@code id} null: "call": null
     * (withdraw it). Otherwise show or update it. A malformed id changes nothing.
     */
    public static Action apply(boolean present, String id) {
        if (!present) {
            return Action.NONE;
        }
        synchronized (LOCK) {
            if (id == null) {
                known = null;
                return Action.CANCEL;  // idempotent: cancelling nothing is harmless
            }
            if (!valid(id)) {
                return Action.NONE;
            }
            known = id;  // even if the notification cannot be shown: no hot re-announce loop
            return Action.SHOW;
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
        }
    }
}
