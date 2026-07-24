package com.qandil.opencodego;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qandil.opencodego.ui.Ui;

public final class PreviewActivity extends Activity {
    private WebView webView;
    private EditText address;
    private TextView desktop;
    private boolean desktopMode;
    private String mobileUserAgent;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::navigateBack);
        }
        build();
        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) url = "about:blank";
        load(url);
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        TextView back = Ui.button(this, "←", false); back.setOnClickListener(view -> navigateBack());
        address = Ui.input(this, "URL", false);
        address.setSingleLine(true);
        address.setOnEditorActionListener((view, actionId, event) -> { load(address.getText().toString()); return true; });
        TextView reload = Ui.button(this, "↻", false); reload.setOnClickListener(view -> webView.reload());
        desktop = Ui.button(this, "Desktop", false); desktop.setOnClickListener(view -> toggleDesktop());
        TextView external = Ui.button(this, "↗", false);
        external.setOnClickListener(view -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webView.getUrl()))); }
            catch (Exception error) { Ui.toast(this, "Нет приложения для URL"); }
        });
        top.addView(back); top.addView(Ui.horizontalSpace(this, 6));
        top.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(Ui.horizontalSpace(this, 6)); top.addView(reload);
        top.addView(Ui.horizontalSpace(this, 6)); top.addView(desktop);
        top.addView(Ui.horizontalSpace(this, 6)); top.addView(external);
        root.addView(top);

        webView = new WebView(this);
        webView.setBackgroundColor(Ui.BG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setMediaPlaybackRequiresUserGesture(false);
        mobileUserAgent = settings.getUserAgentString();
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "file".equals(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) { address.setText(url); }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.d("PreviewConsole", message.message() + " @" + message.lineNumber());
                return true;
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void load(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return;
        if (!url.contains("://") && !url.startsWith("file:") && !url.startsWith("about:")) url = "http://" + url;
        address.setText(url);
        webView.loadUrl(url);
    }

    private void toggleDesktop() {
        desktopMode = !desktopMode;
        if (desktopMode) {
            webView.getSettings().setUserAgentString(
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36");
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setLoadWithOverviewMode(true);
            desktop.setText("Mobile");
        } else {
            webView.getSettings().setUserAgentString(mobileUserAgent);
            webView.getSettings().setUseWideViewPort(false);
            webView.getSettings().setLoadWithOverviewMode(false);
            desktop.setText("Desktop");
        }
        webView.reload();
    }

    private void navigateBack() {
        if (webView.canGoBack()) webView.goBack(); else finish();
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { navigateBack(); }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }
}
