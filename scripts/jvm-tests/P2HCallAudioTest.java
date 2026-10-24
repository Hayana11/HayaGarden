package xyz.lovestyle.home.canary;

/** JVM-only coverage: the call audio bridge only ever fetches its own line URLs. */
public final class P2HCallAudioTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";

    public static void main(String[] args) {
        check(("https://love-style.xyz/api/call/" + ID + "/audio/7").equals(CallAudioUrls.audioUrl(ID, 7)),
                "valid line URL");
        check(CallAudioUrls.audioUrl(ID, 0) == null, "seq 0 accepted");
        check(CallAudioUrls.audioUrl(ID, -3) == null, "negative seq accepted");
        check(CallAudioUrls.audioUrl(ID, 1L + Integer.MAX_VALUE) == null, "seq overflow accepted");
        check(CallAudioUrls.audioUrl(null, 1) == null, "null id accepted");
        check(CallAudioUrls.audioUrl("", 1) == null, "empty id accepted");
        check(CallAudioUrls.audioUrl(ID.toUpperCase(), 1) == null, "uppercase id accepted");
        check(CallAudioUrls.audioUrl(ID.substring(1), 1) == null, "short id accepted");
        check(CallAudioUrls.audioUrl(ID + "0", 1) == null, "long id accepted");
        check(CallAudioUrls.audioUrl(ID.substring(2) + "/.", 1) == null, "path characters accepted");
        check(CallAudioUrls.audioUrl("https://evil.example/" + ID, 1) == null, "URL accepted as id");
        check(CallAudioUrls.audioUrl(ID + "\n", 1) == null, "trailing newline accepted");
        System.out.println("P2H call audio JVM tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
