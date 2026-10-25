package xyz.lovestyle.home.canary;

/** JVM-only coverage of the native call engine's protocol side (P2H-3C). */
public final class P2H3CNativeCallTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String API = "https://love-style.xyz/api/call/";

    public static void main(String[] args) {
        urls();
        chain();
        lines();
        sse();
        System.out.println("P2H-3C native call JVM tests passed");
    }

    /** Same endpoints and query shape as the WebView runtime (lib/voiceCall.ts). */
    private static void urls() {
        check("https://love-style.xyz/api/call/ring".equals(NativeCallProtocol.ringUrl()), "ring");
        check((API + ID + "/answer").equals(NativeCallProtocol.answerUrl(ID)), "answer");
        check((API + ID + "/decline").equals(NativeCallProtocol.declineUrl(ID)), "decline");
        check((API + ID + "/hangup").equals(NativeCallProtocol.hangupUrl(ID)), "hangup");
        check((API + ID + "/interrupt?seq=3&prev=2").equals(NativeCallProtocol.interruptUrl(ID, 3, 2)), "interrupt");
        check((API + ID + "/interrupt?seq=3").equals(NativeCallProtocol.interruptUrl(ID, 3, null)), "interrupt, no prev");
        check((API + ID + "/hear?lang=zho&seq=1&prev=0").equals(NativeCallProtocol.hearUrl(ID, 1, 0)), "hear");
        check((API + ID + "/hear?seq=4&prev=3&discard=1").equals(NativeCallProtocol.discardUrl(ID, 4, 3)), "discard");
        check((API + ID + "/stream?after=12").equals(NativeCallProtocol.streamUrl(ID, 12)), "stream");
        check((API + ID + "/stream?after=0").equals(NativeCallProtocol.streamUrl(ID, -5)), "stream clamps after");
        check((API + ID + "/audio/7").equals(NativeCallProtocol.audioUrl(ID, 7)), "audio");
        for (String bad : new String[] {null, "", ID.toUpperCase(), ID + "\n", "https://evil.example/" + ID, ID.substring(1)}) {
            check(NativeCallProtocol.answerUrl(bad) == null, "bad id accepted: " + bad);
            check(NativeCallProtocol.hearUrl(bad, 1, 0) == null, "bad id accepted by hear: " + bad);
            check(NativeCallProtocol.streamUrl(bad, 0) == null, "bad id accepted by stream: " + bad);
        }
        check(NativeCallProtocol.hearUrl(ID, 0, 0) == null, "seq 0 accepted");
        check(NativeCallProtocol.interruptUrl(ID, -1, null) == null, "negative seq accepted");
        check("en".equals(NativeCallProtocol.ringLanguage(" en ")), "language code");
        check(NativeCallProtocol.ringLanguage("") == null, "empty language is the default call");
        check(NativeCallProtocol.ringLanguage("en&x=1") == null, "language injection accepted");
        check(NativeCallProtocol.ringLanguage(null) == null, "null language");
    }

    /** prev is fixed at speech_start, 0 for the first, never wrong for a non-successor. */
    private static void chain() {
        NativeCallProtocol.UtteranceChain chain = new NativeCallProtocol.UtteranceChain();
        check(Integer.valueOf(0).equals(chain.start(1)), "first prev is 0");
        check(Integer.valueOf(1).equals(chain.start(2)), "second prev is 1");
        check(Integer.valueOf(1).equals(chain.start(2)), "a repeated start keeps its prev");
        check(Integer.valueOf(2).equals(chain.start(5)), "gaps are fine");
        check(chain.start(4) == null, "an older seq gets no prev");
        check(Integer.valueOf(2).equals(chain.prev(5)), "prev looked up at utterance time");
        check(chain.prev(9) == null, "unknown seq has no prev");
    }

    private static void lines() {
        NativeCallProtocol.LineQueue queue = new NativeCallProtocol.LineQueue();
        check(!queue.accept(1, "her"), "her line is never voiced");
        check(queue.accept(2, "him"), "his new line is voiced");
        check(!queue.accept(2, "him"), "a repeated line (hear response + stream) is voiced once");
        check(queue.accept(4, "him"), "later line");
        check(!queue.accept(0, "him"), "seq 0 ignored");
        check(queue.lastSeq() == 4, "stream resumes after the last line");
        check(queue.accept(3, "him"), "a late lower seq is still voiced once");
        check(queue.lastSeq() == 4, "lastSeq never goes back");
    }

    /** The server's stream: event/data/id fields, keepalive comments, an event per blank line. */
    private static void sse() {
        NativeCallProtocol.SseParser parser = new NativeCallProtocol.SseParser();
        check(parser.feed(": keepalive") == null, "comment");
        check(parser.feed("") == null, "a blank line with no data dispatches nothing");
        check(parser.feed("id: 3") == null, "id field ignored");
        check(parser.feed("event: line") == null, "event field");
        check(parser.feed("data: {\"seq\":3}") == null, "data field");
        NativeCallProtocol.SseParser.Event event = parser.feed("");
        check(event != null && "line".equals(event.name) && "{\"seq\":3}".equals(event.data), "line event");
        parser.feed("data:a");
        parser.feed("data: b");
        event = parser.feed("");
        check(event != null && "message".equals(event.name) && "a\nb".equals(event.data), "multi-line data, default name");
        parser.feed("event: state");
        parser.feed("data: {\"state\":\"ended\",\"after\":4}");
        event = parser.feed("");
        check(event != null && "state".equals(event.name), "state event");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
