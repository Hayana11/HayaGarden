package xyz.lovestyle.home.canary;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * P2H-3D r4 diagnostic, probe button only, never on a production path: inside a
 * live Telecom call, the app itself (its own uid, not Telecom's uid 1000) asks for
 * the speaker with setCommunicationDevice. Core-Telecom forbids this mix; it exists
 * only to split the Reno16 finding in two:
 *   speaker becomes audible -> the vendor stack keys on who installed the route;
 *   still the earpiece      -> it keys on a Telecom call existing at all.
 * The route is cleared again by the call's own media teardown. One per call media.
 */
final class AppRouteProbe {
    interface Log {
        void line(String text);
    }

    private final AudioManager audio;
    private final Log log;
    private boolean installed;

    AppRouteProbe(AudioManager audio, Log log) {
        this.audio = audio;
        this.log = log;
    }

    /** Main thread. Repeated taps re-request; the result is logged, never acted on. */
    void speaker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            log.line("app route probe: needs API 31");
            return;
        }
        AudioDeviceInfo speaker = null;
        for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = device;
        }
        if (speaker == null) {
            log.line("app route probe: no speaker among communication devices");
            return;
        }
        boolean accepted = audio.setCommunicationDevice(speaker);
        installed |= accepted;
        AudioDeviceInfo now = audio.getCommunicationDevice();
        log.line("app route probe: setCommunicationDevice(speaker#" + speaker.getId() + ") from app uid accepted="
            + accepted + " mode=" + audio.getMode() + " commDevice="
            + (now == null ? "none" : now.getType() + "#" + now.getId()));
    }

    /** Main thread. Idempotent; only undoes a route this probe installed. */
    void clear(String reason) {
        if (!installed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        installed = false;
        audio.clearCommunicationDevice();
        log.line("app route probe: cleared (" + reason + ")");
    }
}
