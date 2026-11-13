package org.freenetproject.mobile.ui.bootstrap.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.preference.PreferenceManager;

import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;
import org.freenetproject.mobile.ui.sites.Web70Net;
import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows the node's web interface, where the Freenet apps such as River run. */
public class BootstrapActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";
    private WebView webView;
    private SharedPreferences preferences;
    private static final String ASSET_PREFIX = "file:///android_asset/";
    private final String netToken = newToken();
    // Publishing is long and one at a time; reading comments must not wait for it.
    private final ExecutorService publishThread = Executors.newSingleThreadExecutor();
    private final ExecutorService requestThreads = Executors.newCachedThreadPool();
    private Web70Net net;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(pickedFiles(result.getResultCode(), result.getData()));
                    fileCallback = null;
                }
            });
    private final ActivityResultLauncher<String[]> mediaPermissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                if (pendingPermission == null) {
                    return;
                }
                if (granted.containsValue(false)) {
                    pendingPermission.deny();
                } else {
                    pendingPermission.grant(pendingPermission.getResources());
                }
                pendingPermission = null;
            });

    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

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

            // The bridge object is visible to every frame, the token only to the app's own top page.
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith(ASSET_PREFIX)) {
                    view.evaluateJavascript("window.__fnToken=" + JSONObject.quote(netToken), null);
                }
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
                    private boolean done;

                    private void go(WebView popup, Uri url) {
                        if (done || url == null || "about".equals(url.getScheme())) {
                            return;
                        }
                        done = true;
                        String internal = internalPage(url);
                        if (internal != null) {
                            web.loadUrl(internal);
                        } else if (!openOutside(url)) {
                            web.loadUrl(url.toString());
                        }
                        popup.stopLoading();
                        popup.post(popup::destroy);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popup, WebResourceRequest request) {
                        go(popup, request.getUrl());
                        return true;
                    }

                    // Some WebView versions load a window.open address without asking first.
                    @Override
                    public void onPageStarted(WebView popup, String url, android.graphics.Bitmap favicon) {
                        go(popup, url == null ? null : Uri.parse(url));
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(target);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                ((ViewGroup) getWindow().getDecorView()).addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), view);
                bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                bars.hide(WindowInsetsCompat.Type.systemBars());
            }

            @Override
            public void onHideCustomView() {
                leaveFullscreen();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    filePicker.launch(pickIntent(params));
                } catch (ActivityNotFoundException | IllegalStateException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            // Camera and microphone only for the app's own Live page, never for remote apps.
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String url = web.getUrl();
                if (!"file".equals(request.getOrigin().getScheme()) || url == null
                        || !url.startsWith(ASSET_PREFIX)) {
                    request.deny();
                    return;
                }
                pendingPermission = request;
                try {
                    mediaPermissions.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
                } catch (IllegalStateException e) {
                    // The activity is already closing: a late request must not take the app down.
                    pendingPermission = null;
                    request.deny();
                }
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
                if (fullscreenView != null) {
                    leaveFullscreen();
                } else if (web.canGoBack()) {
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

    // Pages opened from the Freenet site cannot publish: they ask for the app's own page by
    // opening https://web70.invalid/open?page=...&route=... (a reserved name that never resolves).
    private static final java.util.Set<String> INTERNAL_PAGES = new java.util.HashSet<>(
            java.util.Arrays.asList("video.html", "photos.html", "posts.html"));

    // The page's accept list (for example "image/*,video/*") and "multiple" both reach the picker;
    // the stock intent keeps only the first type and returns a single file.
    private static Intent pickIntent(WebChromeClient.FileChooserParams params) {
        java.util.List<String> types = new java.util.ArrayList<>();
        for (String accept : params.getAcceptTypes()) {
            for (String type : accept.split(",")) {
                if (type.trim().contains("/")) {
                    types.add(type.trim());
                }
            }
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(types.size() == 1 ? types.get(0) : "*/*");
        if (types.size() > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        }
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return intent;
    }

    private static Uri[] pickedFiles(int code, Intent data) {
        if (code != RESULT_OK || data == null) {
            return null;
        }
        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            Uri[] all = new Uri[data.getClipData().getItemCount()];
            for (int i = 0; i < all.length; i++) {
                all[i] = data.getClipData().getItemAt(i).getUri();
            }
            return all;
        }
        return data.getData() == null ? null : new Uri[]{data.getData()};
    }

    private static String internalPage(Uri url) {
        if (!"https".equals(url.getScheme()) || !"web70.invalid".equals(url.getHost())
                || !"/open".equals(url.getPath())) {
            return null;
        }
        String page = url.getQueryParameter("page");
        String route = url.getQueryParameter("route");
        if (page == null || !INTERNAL_PAGES.contains(page)) {
            return null;
        }
        if (route == null || !route.matches("#/[a-z/]{0,30}")) {
            route = "";
        }
        return ASSET_PREFIX + page + route;
    }

    private void leaveFullscreen() {
        if (fullscreenView == null) {
            return;
        }
        ((ViewGroup) getWindow().getDecorView()).removeView(fullscreenView);
        WindowCompat.getInsetsController(getWindow(), fullscreenView).show(WindowInsetsCompat.Type.systemBars());
        fullscreenView = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        publishThread.shutdownNow();
        requestThreads.shutdownNow();
        super.onDestroy();
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

        /** Network requests of the app's own pages; the answer comes back through window.__fnReply. */
        @JavascriptInterface
        public void net(String token, String method, String arguments, String replyId) {
            if (!netToken.equals(token)) {
                return;
            }
            ExecutorService worker = "sitePublish".equals(method) || "pagesPublish".equals(method) ? publishThread : requestThreads;
            worker.execute(() -> {
                String reply;
                try {
                    synchronized (BootstrapActivity.this) {
                        if (net == null) {
                            net = new Web70Net(BootstrapActivity.this);
                        }
                    }
                    reply = net.handle(method, arguments);
                } catch (IOException e) {
                    reply = "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
                }
                String script = "window.__fnReply&&window.__fnReply(" + JSONObject.quote(replyId) + ","
                        + JSONObject.quote(reply) + ")";
                webView.post(() -> {
                    String url = webView.getUrl();
                    if (url != null && url.startsWith(ASSET_PREFIX)) {
                        webView.evaluateJavascript(script, null);
                    }
                });
            });
        }
    }
}
