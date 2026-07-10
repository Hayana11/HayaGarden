package xyz.lovestyle.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Visible Pocket browser used for logging in sites whose cookies the agent later reuses. */
public class PocketBrowserActivity extends Activity {
    private EditText address;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(8);
        bar.setPadding(pad, pad, pad, pad);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        Button go = new Button(this);
        go.setText("Go");
        bar.addView(address, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(go, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        PocketWebViewPolicy.configure(webView);
        root.addView(bar);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        go.setOnClickListener(v -> loadAddress());
        address.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadAddress();
                return true;
            }
            return false;
        });

        String startUrl = getIntent() != null ? getIntent().getStringExtra("url") : null;
        loadUrl(PocketWebViewPolicy.normalizeUrl(startUrl));
    }

    private void loadAddress() {
        loadUrl(PocketWebViewPolicy.normalizeUrl(address.getText().toString()));
    }

    private void loadUrl(String url) {
        address.setText(url);
        webView.loadUrl(url);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
