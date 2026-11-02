package org.freenetproject.mobile.ui.ghostkey;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.freenetproject.mobile.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Makes local test keys with the ghostkey tool. Valid Ghost Keys are signed by Freenet after a
 * donation: these are signed by a master key made here, so Freenet rejects them.
 */
public class GhostKeyActivity extends AppCompatActivity {
    private TextView result;
    private Button generateButton;
    private Button copyButton;
    private String certificate = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.ghost_generator);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView warning = new TextView(this);
        warning.setText(R.string.ghost_warning);
        root.addView(warning);

        generateButton = new MaterialButton(this);
        generateButton.setText(R.string.ghost_generate);
        generateButton.setOnClickListener(v -> generate());
        root.addView(generateButton);

        result = new TextView(this);
        result.setTextIsSelectable(true);
        root.addView(result);

        copyButton = new MaterialButton(this);
        copyButton.setText(R.string.ghost_copy);
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("Ghost Key", certificate)));
        root.addView(copyButton);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void generate() {
        generateButton.setEnabled(false);
        result.setText(R.string.ghost_working);
        new Thread(() -> {
            String text;
            String made = null;
            try {
                File base = new File(getFilesDir(), "ghostkey");
                deleteTree(base);
                File master = folder(base, "master");
                File delegate = folder(base, "delegate");
                File ghost = folder(base, "ghost");
                run("generate-master-key", "--output-dir", master.getPath());
                run("generate-delegate",
                        "--master-signing-key", new File(master, "master_signing_key.pem").getPath(),
                        "--info", "{\"action\":\"test-key\"}",
                        "--output-dir", delegate.getPath());
                run("generate-ghost-key", "--delegate-dir", delegate.getPath(),
                        "--output-dir", ghost.getPath());
                File cert = new File(ghost, "ghost_key_certificate.pem");
                String local = run("verify-ghost-key",
                        "--master-verifying-key", new File(master, "master_verifying_key.pem").getPath(),
                        "--ghost-certificate", cert.getPath());
                made = new String(Files.readAllBytes(cert.toPath()), StandardCharsets.UTF_8);
                text = getString(R.string.ghost_done, firstLine(local));
            } catch (IOException | InterruptedException e) {
                text = getString(R.string.ghost_failed, e.getMessage());
            }
            String shown = text;
            String ready = made;
            runOnUiThread(() -> {
                generateButton.setEnabled(true);
                result.setText(shown);
                certificate = ready;
                copyButton.setVisibility(ready == null ? View.GONE : View.VISIBLE);
            });
        }, "ghost-key").start();
    }

    private static File folder(File base, String name) throws IOException {
        File dir = new File(base, name);
        Files.createDirectories(dir.toPath());
        return dir;
    }

    private String run(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(getApplicationInfo().nativeLibraryDir + "/libghostkey.so");
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException(arguments[0] + " (" + code + "): " + output);
        }
        return output.toString();
    }

    private static String firstLine(String text) {
        return text.trim().split("\n")[0];
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
