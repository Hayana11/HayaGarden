package xyz.lovestyle.home.canary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * P2H-3C: the native call engine's side of the /api/call/* protocol, the same one
 * the WebView runtime speaks (frontend lib/voiceCall.ts). Every URL is built here
 * on the fixed origin from a validated call id, so the engine can never be pointed
 * at another address. Pure Java for JVM tests.
 */
public final class NativeCallProtocol {
    private static final String API = CallAudioUrls.ORIGIN + "/api/call";
    /** Her utterances are sent as Chinese; the server uses the call's own stt setting. */
    public static final String HEAR_LANGUAGE = "zho";

    private NativeCallProtocol() {
    }

    public static String ringUrl() {
        return API + "/ring";
    }

    /** Language codes are short config keys (en, ja…); anything else means the default call. */
    public static String ringLanguage(String language) {
        if (language == null) return null;
        String code = language.trim();
        return code.matches("^[a-z]{2,8}$") ? code : null;
    }

    public static String answerUrl(String callId) {
        return callUrl(callId, "/answer");
    }

    public static String declineUrl(String callId) {
        return callUrl(callId, "/decline");
    }

    public static String hangupUrl(String callId) {
        return callUrl(callId, "/hangup");
    }

    /** Barge-in, as the page sends it at speech_start: seq and the prev fixed then. */
    public static String interruptUrl(String callId, int seq, Integer prev) {
        String base = callUrl(callId, "/interrupt");
        return base == null || seq <= 0 ? null : base + "?" + seqQuery(seq, prev);
    }

    public static String hearUrl(String callId, int seq, Integer prev) {
        String base = callUrl(callId, "/hear");
        return base == null || seq <= 0 ? null : base + "?lang=" + HEAR_LANGUAGE + "&" + seqQuery(seq, prev);
    }

    public static String discardUrl(String callId, int seq, Integer prev) {
        String base = callUrl(callId, "/hear");
        return base == null || seq <= 0 ? null : base + "?" + seqQuery(seq, prev) + "&discard=1";
    }

    public static String streamUrl(String callId, int after) {
        String base = callUrl(callId, "/stream");
        return base == null ? null : base + "?after=" + Math.max(0, after);
    }

    public static String audioUrl(String callId, int seq) {
        return CallAudioUrls.audioUrl(callId, seq);
    }

    private static final String ACTION_URI = "hayagarden:call-action/";

    /**
     * A notification action's identity: its call's generation and the action, as the
     * Intent data. PendingIntents are matched by Intent.filterEquals, which compares
     * data but not extras, so each call gets its own tokens: FLAG_UPDATE_CURRENT for
     * a new call can never rewrite an old call's button (it would also replace that
     * button's extras). Extras alone would not do.
     */
    public static String actionUri(int gen, String action) {
        if (gen <= 0 || action == null || action.isEmpty()) return null;
        return ACTION_URI + gen + "/" + action;
    }

    /**
     * The generation a received action names, checked twice: its data URI must be
     * actionUri(gen, action) and its extra must say the same gen. No data (the
     * acceptance screen's own buttons) returns extraGen as it is; any mismatch or
     * malformed URI returns -1, which no call matches.
     */
    public static int actionGeneration(String data, String action, int extraGen) {
        if (data == null) return extraGen;
        if (extraGen <= 0 || action == null || !data.startsWith(ACTION_URI)) return -1;
        String rest = data.substring(ACTION_URI.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || !rest.substring(slash + 1).equals(action)) return -1;
        String digits = rest.substring(0, slash);
        if (!digits.matches("^[1-9][0-9]{0,8}$")) return -1;
        int gen = Integer.parseInt(digits);
        return gen == extraGen ? gen : -1;
    }

    private static String callUrl(String callId, String path) {
        return CallAudioUrls.validCallId(callId) ? API + "/" + callId + path : null;
    }

    private static String seqQuery(int seq, Integer prev) {
        return prev == null ? "seq=" + seq : "seq=" + seq + "&prev=" + prev;
    }

    /**
     * Her utterances in one call as a predecessor chain (lib/voiceCall.ts UtteranceChain):
     * each interrupt/hear/discard names the seq she started just before it (0 for the
     * first), fixed at speech_start, so the server orders them even if Scribe answers
     * out of order.
     */
    public static final class UtteranceChain {
        private int last;
        private final Map<Integer, Integer> prevBySeq = new HashMap<>();

        /** speech_start(seq): fix and return its prev, or null when seq is not a successor. */
        public synchronized Integer start(int seq) {
            Integer known = prevBySeq.get(seq);
            if (known != null) return known;
            if (seq <= last) return null;
            prevBySeq.put(seq, last);
            last = seq;
            return prevBySeq.get(seq);
        }

        public synchronized Integer prev(int seq) {
            return prevBySeq.get(seq);
        }
    }

    /** His lines to voice, once each, in seq order; her lines and repeats are never queued. */
    public static final class LineQueue {
        private final Set<Integer> known = new HashSet<>();
        private int lastSeq;

        /** A line arrived (stream or hear response); true when it is a new line of his to play. */
        public synchronized boolean accept(int seq, String speaker) {
            if (seq <= 0 || !known.add(seq)) return false;
            if (seq > lastSeq) lastSeq = seq;
            return "him".equals(speaker);
        }

        /** The stream reconnects after the last line it saw. */
        public synchronized int lastSeq() {
            return lastSeq;
        }
    }

    /**
     * Server-sent events, line by line: "event:" and "data:" fields, dispatched on the
     * blank line that ends an event. Comments (":keepalive") and other fields are ignored.
     */
    public static final class SseParser {
        public static final class Event {
            public final String name;
            public final String data;

            Event(String name, String data) {
                this.name = name;
                this.data = data;
            }
        }

        private String name = "message";
        private StringBuilder data;

        /** One line without its newline; returns the event it completes, or null. */
        public Event feed(String line) {
            if (line == null) return null;
            if (line.isEmpty()) {
                Event event = data == null ? null : new Event(name, data.toString());
                name = "message";
                data = null;
                return event;
            }
            if (line.startsWith(":")) return null;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("event".equals(field)) {
                name = value;
            } else if ("data".equals(field)) {
                if (data == null) data = new StringBuilder(value);
                else data.append('\n').append(value);
            }
            return null;
        }
    }
}
