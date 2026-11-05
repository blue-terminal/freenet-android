package org.freenetproject.mobile.ui.bootstrap.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;

/** Shows the node's web interface, where the Freenet apps such as River run. */
public class BootstrapActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";
    private WebView webView;
    private SharedPreferences preferences;

    @SuppressLint("SetJavaScriptEnabled") // Freenet apps need it; they only load from the local node
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);

        WebView web = findViewById(R.id.bootstrap_webview);
        TextView address = findViewById(R.id.web_address);
        // Store web ref for onResume text-zoom updates
        webView = web;
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        findViewById(R.id.web_back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        ImageButton editUrl = findViewById(R.id.web_edit_url);
        editUrl.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setText(address.getText());
            input.setSelection(input.getText().length());
            input.setSingleLine(true);
            input.setHint(R.string.web_edit_url_hint);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.web_edit_url_title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String url = input.getText().toString().trim();
                        if (!url.isEmpty()) web.loadUrl(url);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        WebSettings settings = web.getSettings();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        settings.setTextZoom(Integer.parseInt(preferences.getString("web_text_size", "100")));
        if (preferences.getBoolean("web_keep_screen", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Consente alle pagine in file:// (asset locali come loading.html / web70_preview.html)
        // di fare fetch verso il nodo Freenet su 127.0.0.1 e nodi remoti sulla LAN.
        settings.setAllowUniversalAccessFromFileURLs(true);
        // Espone startNode() e getNodeStatus() a loading.html via Android.startNode()
        web.addJavascriptInterface(new NodeBridge(), "Android");
        // Freenet opens links between its apps as new windows, from inside sandboxed frames.
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openOutside(request.getUrl());
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // Only the address: the query can hold the node's access token.
                address.setText(Uri.parse(url).buildUpon().clearQuery().build().toString());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // Without a WebChromeClient the WebView silently drops the pages' confirmation dialogs.
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message resultMsg) {
                // A window that only reports where it wants to go: its address then opens here.
                WebView target = new WebView(BootstrapActivity.this);
                target.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popup, WebResourceRequest request) {
                        Uri url = request.getUrl();
                        if (!openOutside(url)) {
                            web.loadUrl(url.toString());
                        }
                        popup.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(target);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e("FreenetWeb", message.message());
                }
                return true;
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });
        String url = getIntent().getStringExtra(EXTRA_URL);
        web.loadUrl(url != null ? url : Manager.WEB_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null && preferences != null) {
            webView.getSettings().setTextZoom(
                    Integer.parseInt(preferences.getString("web_text_size", "100")));
        }
    }

    /** Returns false for local/LAN Freenet nodes (any host on WEB_PORT). Anything else leaves the app. */
    private boolean openOutside(Uri url) {
        // Navigazione tra asset locali (file:///android_asset/) — deve restare nel WebView
        if ("file".equals(url.getScheme())) {
            return false;
        }
        if ("127.0.0.1".equals(url.getHost()) && url.getPort() == Manager.WEB_PORT) {
            return false;
        }
        // LAN Freenet nodes (192.168.x.x:7509, 10.x.x.x:7509 …) stay in the WebView
        if (url.getPort() == Manager.WEB_PORT) {
            return false;
        }
        // Invite form and Ghost Key page stay in the app: the address bar shows where you are.
        String host = url.getHost();
        if ("https".equals(url.getScheme()) && host != null
                && (host.equals("freenet.org") || host.endsWith(".freenet.org"))) {
            return false;
        }
        // A page must not be able to open other apps through their own link types.
        String scheme = url.getScheme();
        if ("https".equals(scheme) || "http".equals(scheme)) {
            Intent browser = new Intent(Intent.ACTION_VIEW, url);
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(browser);
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    /** Esposta a loading.html e index.html via window.Android */
    private class NodeBridge {
        @JavascriptInterface
        public void startNode() {
            Manager.getInstance().startService(BootstrapActivity.this);
        }

        @JavascriptInterface
        public String getStatus() {
            Manager.Status s = Manager.getInstance().getStatus().getValue();
            return s != null ? s.name() : "STOPPED";
        }

        /** Apre un URL https:// nel browser esterno del dispositivo. */
        @JavascriptInterface
        public void openExternal(String url) {
            if (url == null) return;
            String s = url.trim();
            if (!s.startsWith("https://") && !s.startsWith("http://")) return;
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(s));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(browser);
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }
}
