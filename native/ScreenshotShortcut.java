package xyz.lovestyle.home.canary;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/** The owner invokes the system screenshot UI using Android's accessibility shortcut. */
public class ScreenshotShortcut extends AccessibilityService {
    private final AccessibilityButtonController.AccessibilityButtonCallback shortcut =
        new AccessibilityButtonController.AccessibilityButtonCallback() {
            @Override
            public void onClicked(AccessibilityButtonController controller) {
                if (Build.VERSION.SDK_INT < 28 || !performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)) {
                    Toast.makeText(ScreenshotShortcut.this, "系统未能截屏", Toast.LENGTH_SHORT).show();
                }
            }
        };

    @Override protected void onServiceConnected() {
        getAccessibilityButtonController().registerAccessibilityButtonCallback(shortcut);
    }
    @Override public void onDestroy() {
        getAccessibilityButtonController().unregisterAccessibilityButtonCallback(shortcut);
        super.onDestroy();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
