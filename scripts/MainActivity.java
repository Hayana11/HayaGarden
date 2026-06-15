package xyz.lovestyle.home;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private AppTracker tracker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tracker = new AppTracker(getApplicationContext());
        if (!tracker.hasPermission()) {
            tracker.openPermissionSettings();
        } else {
            tracker.start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tracker != null) tracker.stop();
    }
}
