package org.freenetproject.mobile.ui.ghostkey;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes local test keys with the ghostkey tool. Valid Ghost Keys are signed by Freenet after a
 * donation: these are signed by a master key made here, so Freenet rejects them.
 */
public class GhostKeyActivity extends AppCompatActivity {
    // Only the certificate: a pasted secret key must not end up in the temporary file.
    private static final Pattern CERTIFICATE = Pattern.compile(
            "-----BEGIN GHOSTKEY_CERTIFICATE_V1-----.*?-----END GHOSTKEY_CERTIFICATE_V1-----",
            Pattern.DOTALL);

    private EditText certificateInput;
    private Button verifyButton;
    private TextView verifyResult;
    private TextView result;
    private Button generateButton;
    private Button copyButton;
    private Button deleteButton;
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
        copyButton.setOnClickListener(v -> copyKey());
        root.addView(copyButton);

        deleteButton = new MaterialButton(this);
        deleteButton.setText(R.string.ghost_delete);
        deleteButton.setVisibility(new File(getFilesDir(), "ghostkey").exists() ? View.VISIBLE : View.GONE);
        deleteButton.setOnClickListener(v -> {
            deleteTree(new File(getFilesDir(), "ghostkey"));
            certificate = null;
            copyButton.setVisibility(View.GONE);
            deleteButton.setVisibility(View.GONE);
            result.setText(R.string.ghost_deleted);
        });
        root.addView(deleteButton);

        TextView verifyTitle = new TextView(this);
        verifyTitle.setText(R.string.ghost_verify_help);
        verifyTitle.setPadding(0, padding, 0, 0);
        root.addView(verifyTitle);

        certificateInput = new EditText(this);
        certificateInput.setHint(R.string.ghost_verify_hint);
        certificateInput.setMinLines(4);
        certificateInput.setGravity(Gravity.TOP | Gravity.START);
        root.addView(certificateInput);

        verifyButton = new MaterialButton(this);
        verifyButton.setText(R.string.ghost_verify);
        verifyButton.setOnClickListener(v -> verify());
        root.addView(verifyButton);

        verifyResult = new TextView(this);
        verifyResult.setTextIsSelectable(true);
        root.addView(verifyResult);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** The vault imports certificate and signing key together, as one text. */
    private void copyKey() {
        ClipData clip = ClipData.newPlainText("Ghost Key", certificate);
        // The text holds the secret key: Android must not show it in the clipboard preview.
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(this, R.string.ghost_copied, Toast.LENGTH_SHORT).show();
    }

    /**
     * Checks the chain from Freenet's master key (the tool's default, with no key given).
     * If that fails and a test key was made on this phone, checks it against that key too,
     * so a test certificate is told apart from a broken one.
     */
    private void verify() {
        Matcher block = CERTIFICATE.matcher(certificateInput.getText());
        if (!block.find()) {
            verifyResult.setText(R.string.ghost_verify_no_certificate);
            return;
        }
        String text = block.group() + "\n";
        verifyButton.setEnabled(false);
        verifyResult.setText(R.string.ghost_working);
        new Thread(() -> {
            String shown;
            File file = new File(getCacheDir(), "certificate-to-verify.pem");
            try {
                Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
                shown = getString(R.string.ghost_verify_valid,
                        run("verify-ghost-key", "--ghost-certificate", file.getPath()).trim());
            } catch (IOException | InterruptedException e) {
                shown = verifyWithOwnKey(file, e);
            } finally {
                file.delete();
            }
            String result = shown;
            runOnUiThread(() -> {
                verifyButton.setEnabled(true);
                verifyResult.setText(result);
            });
        }, "ghost-key-verify").start();
    }

    /**
     * Second pass: the certificate did not come from Freenet, so it is checked against the
     * master key the generator left in app storage. Tells "test key made here" apart from
     * "certificate is broken".
     */
    private String verifyWithOwnKey(File certificate, Exception first) {
        File own = new File(new File(new File(getFilesDir(), "ghostkey"), "master"),
                "master_verifying_key.pem");
        if (own.isFile() && own.length() > 0) {
            try {
                String output = run("verify-ghost-key",
                        "--master-verifying-key", own.getPath(),
                        "--ghost-certificate", certificate.getPath()).trim();
                return getString(R.string.ghost_verify_local, output);
            } catch (IOException | InterruptedException ignored) {
                // Not this phone's test key either: report the Freenet result below.
            }
        }
        return getString(R.string.ghost_verify_invalid, first.getMessage());
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
                made = new String(Files.readAllBytes(cert.toPath()), StandardCharsets.UTF_8).trim()
                        + "\n" + new String(Files.readAllBytes(
                                new File(ghost, "ghost_key_signing_key.pem").toPath()),
                                StandardCharsets.UTF_8).trim() + "\n";
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
                deleteButton.setVisibility(ready == null ? View.GONE : View.VISIBLE);
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
