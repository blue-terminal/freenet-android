package org.freenetproject.mobile.ui.bootstrap.activity;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("127.0.0.1".equals(url.getHost()) && url.getPort() == Manager.WEB_PORT) {
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
}
