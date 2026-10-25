package xyz.lovestyle.home.canary;

/**
 * P2H-3C: the start requests one call run owns. Every onStartCommand that arrives
 * while a run is current belongs to it (the dial, its Answer/Hangup/route taps, a
 * refused second dial, a stale notification tap); a new dial opens a new run with
 * its own ids. When the run finishes it calls stopSelf(stopId()): the newest id it
 * owns. A start request the run does not own (an immediate redial) is newer, so
 * Android keeps the service. Never a service-wide "last start id". Pure Java.
 */
public final class CallStartIds {
    private int owned;

    public CallStartIds(int startId) {
        own(startId);
    }

    public synchronized void own(int startId) {
        if (startId > owned) owned = startId;
    }

    public synchronized int stopId() {
        return owned;
    }
}
