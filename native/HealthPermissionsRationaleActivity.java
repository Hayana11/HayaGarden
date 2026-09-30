package xyz.lovestyle.home.canary;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Local Health Connect permission rationale for the sideloaded Canary build.
 *
 * This screen contains no credentials or health data.
 */
public final class HealthPermissionsRationaleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.WHITE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density + 0.5f);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Elpis Canary Health Bridge");
        title.setTextColor(Color.rgb(32, 32, 32));
        title.setTextSize(20);
        title.setPadding(0, 0, 0, padding);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(
                "Health Connect permission rationale\\n\\n"
                        + "Elpis Canary requests READ access only for heart rate, steps, and sleep. "
                        + "It does not request Health Connect WRITE permissions.\\n\\n"
                        + "Health data is used for the owner's personal Elpis health context "
                        + "and may sync to the owner's own HayaGarden server.\\n\\n"
                        + "You can revoke Health Connect access at any time from Android Settings.");
        body.setTextColor(Color.rgb(48, 48, 48));
        body.setTextSize(16);
        content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView.addView(content);
        setContentView(scrollView);
    }
}
