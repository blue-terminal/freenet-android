package org.freenetproject.mobile.ui.bootstrap.activity;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;

/** Shows the node's web interface, where the Freenet apps such as River run. */
public class BootstrapActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";

    @SuppressLint("SetJavaScriptEnabled") // Freenet apps need it; they only load from the local node
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);

        WebView web = findViewById(R.id.bootstrap_webview);
        TextView address = findViewById(R.id.web_address);
        findViewById(R.id.web_back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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

    /** Returns false for the local node, which stays in the app. Anything else leaves it. */
    private boolean openOutside(Uri url) {
        if ("127.0.0.1".equals(url.getHost()) && url.getPort() == Manager.WEB_PORT) {
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
}
