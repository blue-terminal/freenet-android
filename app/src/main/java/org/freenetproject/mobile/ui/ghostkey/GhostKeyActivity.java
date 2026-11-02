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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.freenetproject.mobile.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes keys with the ghostkey tool and checks them. Freenet only accepts a Ghost Key it has
 * signed itself after a donation, so the keys made here are signed by this phone's own master
 * key: they work between the people who use this app and add each other's public key.
 */
public class GhostKeyActivity extends AppCompatActivity {
    // Only the certificate: a pasted secret key must not end up in the temporary file.
    private static final Pattern CERTIFICATE = Pattern.compile(
            "-----BEGIN GHOSTKEY_CERTIFICATE_V1-----.*?-----END GHOSTKEY_CERTIFICATE_V1-----",
            Pattern.DOTALL);
    // The public half of someone else's master key, which is what makes their keys verifiable.
    private static final Pattern PUBLIC_KEY = Pattern.compile(
            "-----BEGIN VERIFYING_KEY_V1-----.*?-----END VERIFYING_KEY_V1-----",
            Pattern.DOTALL);
    /** Written into every key made here, so the check prints where the key comes from. */
    private static final String INFO = "{\"action\":\"freenet-android\"}";

    private EditText certificateInput;
    private Button verifyButton;
    private TextView verifyResult;
    private TextView result;
    private Button generateButton;
    private Button copyButton;
    private Button shareButton;
    private Button historyButton;
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

        shareButton = new MaterialButton(this);
        shareButton.setText(R.string.ghost_share);
        shareButton.setVisibility(ownKey().isFile() ? View.VISIBLE : View.GONE);
        shareButton.setOnClickListener(v -> sharePublicKey());
        root.addView(shareButton);

        historyButton = new MaterialButton(this);
        historyButton.setText(R.string.ghost_history);
        historyButton.setVisibility(historyFile().isFile() ? View.VISIBLE : View.GONE);
        historyButton.setOnClickListener(v -> showHistory());
        root.addView(historyButton);

        deleteButton = new MaterialButton(this);
        deleteButton.setText(R.string.ghost_delete);
        deleteButton.setVisibility(new File(getFilesDir(), "ghostkey").exists() ? View.VISIBLE : View.GONE);
        deleteButton.setOnClickListener(v -> {
            deleteTree(new File(getFilesDir(), "ghostkey"));
            certificate = null;
            copyButton.setVisibility(View.GONE);
            shareButton.setVisibility(View.GONE);
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

    /** This phone's master verifying key: public, and the piece another person needs. */
    private File ownKey() {
        return new File(new File(new File(getFilesDir(), "ghostkey"), "master"),
                "master_verifying_key.pem");
    }

    /** The public keys of the people added here, one PEM file each, named after the person. */
    private File peopleDir() {
        return new File(getFilesDir(), "ghostkey-people");
    }

    /**
     * Copies the public half of this phone's master key. It carries no secret: the other person
     * adds it in their app and from then on the keys made here check out for them.
     */
    private void sharePublicKey() {
        File own = ownKey();
        if (!own.isFile()) {
            Toast.makeText(this, R.string.ghost_share_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String text = new String(Files.readAllBytes(own.toPath()), StandardCharsets.UTF_8).trim();
            getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("Freenet public key", text + "\n"));
            Toast.makeText(this, R.string.ghost_shared, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.ghost_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Reads the pasted text: a certificate is checked, a public key is added to the people
     * list. Checking goes from Freenet's key to this phone's key to the people added here,
     * so the answer says who the certificate belongs to instead of only "not valid".
     */
    private void verify() {
        CharSequence pasted = certificateInput.getText();
        Matcher block = CERTIFICATE.matcher(pasted);
        if (!block.find()) {
            Matcher key = PUBLIC_KEY.matcher(pasted);
            if (key.find()) {
                askNameAndAdd(key.group() + "\n");
            } else {
                verifyResult.setText(R.string.ghost_verify_no_certificate);
            }
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
                shown = verifyWithKnownKeys(file, e);
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

    /** Stores a pasted public key under a name, so a later check can say whose key it is. */
    private void askNameAndAdd(String pem) {
        EditText name = new EditText(this);
        name.setHint(R.string.ghost_key_name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ghost_key_add)
                .setView(name)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String person = name.getText().toString().trim();
                    if (person.isEmpty()) {
                        person = getString(R.string.ghost_key_unnamed);
                    }
                    String safe = person.replaceAll("[^\\p{L}\\p{N} _-]", "_");
                    try {
                        Files.createDirectories(peopleDir().toPath());
                        Files.write(new File(peopleDir(), safe + ".pem").toPath(),
                                pem.getBytes(StandardCharsets.UTF_8));
                        certificateInput.setText("");
                        verifyResult.setText(getString(R.string.ghost_key_added, person));
                    } catch (IOException e) {
                        verifyResult.setText(getString(R.string.ghost_failed, e.getMessage()));
                    }
                })
                .show();
    }

    /**
     * Second pass: the certificate did not come from Freenet, so it is checked against this
     * phone's master key and then against every person added here.
     */
    private String verifyWithKnownKeys(File certificate, Exception first) {
        File own = ownKey();
        if (own.isFile() && own.length() > 0) {
            String output = check(own, certificate);
            if (output != null) {
                return getString(R.string.ghost_verify_local, output);
            }
        }
        File[] people = peopleDir().listFiles();
        if (people != null) {
            for (File person : people) {
                String output = check(person, certificate);
                if (output != null) {
                    String name = person.getName().replaceFirst("\\.pem$", "");
                    return getString(R.string.ghost_verify_person, name, output);
                }
            }
        }
        return getString(R.string.ghost_verify_invalid, first.getMessage());
    }

    /** The tool's output when the certificate matches that master key, otherwise null. */
    private String check(File masterVerifyingKey, File certificate) {
        try {
            return run("verify-ghost-key",
                    "--master-verifying-key", masterVerifyingKey.getPath(),
                    "--ghost-certificate", certificate.getPath()).trim();
        } catch (IOException | InterruptedException notThisOne) {
            return null;
        }
    }

    /**
     * Keeps the master and delegate keys already on the phone and only makes the ghost key
     * again: it is quicker, and the identity stays the same, so the public key already given
     * to other people keeps recognising the new key.
     */
    private void generate() {
        generateButton.setEnabled(false);
        result.setText(R.string.ghost_working);
        new Thread(() -> {
            String text;
            String made = null;
            try {
                File base = new File(getFilesDir(), "ghostkey");
                File master = folder(base, "master");
                File delegate = folder(base, "delegate");
                File ghost = folder(base, "ghost");
                if (!new File(master, "master_signing_key.pem").isFile()) {
                    run("generate-master-key", "--output-dir", master.getPath());
                }
                if (!new File(delegate, "delegate_signing_key.pem").isFile()) {
                    run("generate-delegate",
                            "--master-signing-key", new File(master, "master_signing_key.pem").getPath(),
                            "--info", INFO,
                            "--output-dir", delegate.getPath());
                }
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
                remember(new String(Files.readAllBytes(cert.toPath()), StandardCharsets.UTF_8));
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
                shareButton.setVisibility(ready == null ? View.GONE : View.VISIBLE);
                historyButton.setVisibility(historyFile().isFile() ? View.VISIBLE : View.GONE);
                deleteButton.setVisibility(ready == null ? View.GONE : View.VISIBLE);
            });
        }, "ghost-key").start();
    }

    /** One line per key made here: when it was made and how it starts. No secrets. */
    private File historyFile() {
        return new File(getFilesDir(), "ghostkey-history.txt");
    }

    /**
     * Writes down a key that has just been made. Only the date and the first characters of
     * the certificate go in, enough to tell the keys apart without keeping a copy of them.
     */
    private void remember(String certificatePem) {
        String start = certificatePem.replaceAll("-----[A-Z_0-9 ]+-----", "")
                .replaceAll("\\s", "");
        String mark = start.length() > 12 ? start.substring(0, 12) : start;
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())
                + "  " + mark + "…\n";
        try (FileWriter writer = new FileWriter(historyFile(), true)) {
            writer.write(line);
        } catch (IOException ignored) {
            // The history is a convenience: a key is made whether it can be written or not.
        }
    }

    private void showHistory() {
        String text;
        try {
            text = new String(Files.readAllBytes(historyFile().toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        if (text.trim().isEmpty()) {
            text = getString(R.string.ghost_history_empty);
        }
        TextView shown = new TextView(this);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        shown.setPadding(padding, padding, padding, padding);
        shown.setTextIsSelectable(true);
        shown.setText(text);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(shown);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ghost_history)
                .setView(scroll)
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.ghost_history_clear, (dialog, which) -> {
                    historyFile().delete();
                    historyButton.setVisibility(View.GONE);
                })
                .show();
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
