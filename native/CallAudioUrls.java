package xyz.lovestyle.home.canary;

import java.util.regex.Pattern;

/**
 * P2H-1: the only audio the call bridge will fetch. The page names a line by
 * call id and seq; the URL (fixed host, fixed path) is built here, so the
 * bridge can never be told to play an arbitrary address. Pure Java for JVM tests.
 */
public final class CallAudioUrls {
    public static final String ORIGIN = "https://love-style.xyz";
    private static final Pattern CALL_ID = Pattern.compile("^[0-9a-f]{32}$");

    private CallAudioUrls() {
    }

    public static boolean validCallId(String callId) {
        return callId != null && CALL_ID.matcher(callId).matches();
    }

    public static boolean validSeq(long seq) {
        return seq > 0 && seq <= Integer.MAX_VALUE;
    }

    /** The line's audio URL, or null when either argument is not acceptable. */
    public static String audioUrl(String callId, long seq) {
        if (!validCallId(callId) || !validSeq(seq)) {
            return null;
        }
        return ORIGIN + "/api/call/" + callId + "/audio/" + seq;
    }
}
