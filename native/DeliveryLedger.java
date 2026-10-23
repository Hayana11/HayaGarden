package xyz.lovestyle.home.canary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Device-side "show each wake id once" ledger, free of Android types so it can be
 * tested on a plain JVM. NotificationSupport backs it with SharedPreferences.
 * There is deliberately no "highest id seen" cursor: with two pollers it cannot
 * prove that every lower id was shown, so acks are always explicit per id.
 *
 * The check -> show -> record sequence runs under one lock shared by every
 * caller in the process (NotificationPollWorker and PushService), and records
 * only after the notification was actually shown.
 */
final class DeliveryLedger {
    enum Result { SHOWN, ALREADY_SHOWN, NOT_SHOWN }

    interface Store {
        String readShownIds();
        /** Must be durable when it returns (SharedPreferences.commit, not apply). */
        void write(String shownIds);
    }

    interface Display {
        boolean show();
    }

    static final int KEPT = 64;
    private static final Object LOCK = new Object();

    private DeliveryLedger() {}

    static Result deliver(Store store, String id, Display display) {
        synchronized (LOCK) {
            List<String> shown = parse(store.readShownIds());
            if (shown.contains(id)) {
                return Result.ALREADY_SHOWN;
            }
            // Show first, record after: a crash in between repeats the notification
            // (same notification id, so it replaces itself) instead of losing it.
            if (!display.show()) {
                return Result.NOT_SHOWN;
            }
            shown.add(id);
            while (shown.size() > KEPT) {
                shown.remove(0);
            }
            store.write(String.join("\n", shown));
            return Result.SHOWN;
        }
    }

    private static List<String> parse(String raw) {
        List<String> ids = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        for (String id : Arrays.asList(raw.split("\n"))) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
