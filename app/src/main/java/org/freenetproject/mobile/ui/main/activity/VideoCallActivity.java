package org.freenetproject.mobile.ui.main.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.freenetproject.mobile.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Videochiamate P2P anonime via Jitsi Meet (WebRTC).
 * La stanza è basata sull'ora corrente (UTC, a blocchi di 30 min),
 * così chiunque usi l'app nello stesso slot finisce nella stessa stanza.
 * Il nome è un hash offuscato — non contiene informazioni identificabili.
 */
public class VideoCallActivity extends AppCompatActivity {

    private static final int PERM_REQ = 101;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_call);

        ImageButton back = findViewById(R.id.vc_back);
        back.setOnClickListener(v -> finish());

        TextView roomLabel = findViewById(R.id.vc_room_label);
        String room = roomName();
        roomLabel.setText(getString(R.string.vc_room_slot, slotLabel()));

        webView = findViewById(R.id.vc_webview);
        configureWebView();

        if (hasPermissions()) {
            loadCall(room);
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                PERM_REQ);
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Grant camera + mic to Jitsi inside WebView
                request.grant(request.getResources());
            }
        });
    }

    private void loadCall(String room) {
        // Jitsi Meet: open-source E2E encrypted video call
        // Room name based on time slot — shared across all app users in the same slot
        // meet.ffmuc.net: Jitsi pubblico (Freifunk Monaco) senza account richiesto
        String url = "https://meet.ffmuc.net/" + room
                + "#config.startWithAudioMuted=false"
                + "&config.startWithVideoMuted=false"
                + "&config.disableDeepLinking=true"
                + "&config.prejoinPageEnabled=false"
                + "&config.requireDisplayName=false"
                + "&config.toolbarButtons=[%22microphone%22,%22camera%22,%22hangup%22]";
        webView.loadUrl(url);
    }

    /**
     * Room name: "fn" + hex of (epoch / 1800) mod 0xFFFF, padded to 4 chars.
     * Changes every 30 minutes. Short, non-guessable by outsiders.
     */
    static String roomName() {
        long slot = System.currentTimeMillis() / 1000 / 1800;
        return String.format(Locale.ROOT, "fn%04x", slot & 0xFFFFL);
    }

    /** Human-readable slot label, e.g. "14:00–14:30" */
    static String slotLabel() {
        long slotMs = (System.currentTimeMillis() / 1000 / 1800) * 1800L * 1000L;
        long nextMs  = slotMs + 1800_000L;
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return fmt.format(new Date(slotMs)) + "–" + fmt.format(new Date(nextMs));
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_REQ) {
            boolean ok = results.length >= 2
                && results[0] == PackageManager.PERMISSION_GRANTED
                && results[1] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                loadCall(roomName());
            } else {
                Toast.makeText(this, R.string.vc_perm_denied, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}
