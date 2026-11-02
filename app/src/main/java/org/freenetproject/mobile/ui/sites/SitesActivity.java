package org.freenetproject.mobile.ui.sites;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;

import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;
import org.freenetproject.mobile.ui.bootstrap.activity.BootstrapActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes HTML websites on the phone and publishes them on Freenet with fdev. */
public class SitesActivity extends AppCompatActivity {
    private static final Pattern SITE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern PAGE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}\\.html");
    private static final Pattern SITE_URL = Pattern.compile(
            "http://127\\.0\\.0\\.1:" + Manager.WEB_PORT + "/v1/contract/web/[A-Za-z0-9]+/");
    private static final Pattern EDITABLE = Pattern.compile(".*\\.(html?|css|js)");
    private static final long MAX_IMPORT_BYTES = 200L * 1024 * 1024;
    private static final int PUBLISH_TIMEOUT_SECONDS = 300;
    private static final String INDEX = "index.html";
    private static final String TEMPLATE =
            "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                    + "<title>Il mio sito</title>\n</head>\n<body>\n"
                    + "<h1>Benvenuto</h1>\n<p>Scrivi qui il tuo sito.</p>\n</body>\n</html>\n";

    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::importFolder);
    private Spinner siteSpinner;
    private Spinner pageSpinner;
    private EditText editor;
    private TextView message;
    private Button publishButton;
    private Button openButton;
    private File currentFile = null;
    private Vault vault;
    private Button copyButton;
    private String publishedUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.sites_title);
        try {
            vault = new Vault(this);
        } catch (IOException e) {
            // Without the key nothing may be read or overwritten.
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        root.setFitsSystemWindows(true);

        siteSpinner = new Spinner(this);
        pageSpinner = new Spinner(this);
        root.addView(row(siteSpinner, button(R.string.sites_new_site, v -> askNewSite())));
        root.addView(row(pageSpinner, button(R.string.sites_new_page, v -> askNewPage())));
        root.addView(button(R.string.sites_open_folder, v -> folderPicker.launch(null)));

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(14);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        publishButton = button(R.string.sites_publish, v -> askPublish());
        actions.addView(button(R.string.sites_save, v -> {
            if (save()) {
                message.setText(R.string.sites_saved);
            }
        }), weight());
        actions.addView(button(R.string.sites_preview, v -> preview()), weight());
        actions.addView(publishButton, weight());
        root.addView(actions);

        message = new TextView(this);
        message.setTextIsSelectable(true);
        root.addView(message);

        openButton = button(R.string.sites_open, v -> openPublished());
        copyButton = button(R.string.sites_copy_link, v -> {
            copy(publishedUrl);
            message.setText(getString(R.string.sites_link_copied, publishedUrl));
        });
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.addView(openButton, weight());
        linkRow.addView(copyButton, weight());
        openButton.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        root.addView(linkRow);

        setContentView(root);

        siteSpinner.setOnItemSelectedListener(new Selected(this::siteChanged));
        pageSpinner.setOnItemSelectedListener(new Selected(this::pageChanged));
        if (sites().isEmpty()) {
            createSite("mio-sito");
        }
        refreshSites(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private LinearLayout row(View first, View second) {
        LinearLayout row = new LinearLayout(this);
        row.addView(first, weight());
        row.addView(second);
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        int margin = Math.round(4 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    /** Only the main action stands out: the others use the quieter color. */
    private Button button(int text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setLetterSpacing(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setOnClickListener(listener);
        if (text != R.string.sites_publish) {
            button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.colorSurface)));
            button.setTextColor(Color.WHITE);
        }
        return button;
    }

    private File sitesDir() {
        return new File(getFilesDir(), "sites");
    }

    private List<String> sites() {
        return list(sitesDir(), File::isDirectory);
    }

    private List<String> pages(String site) {
        return list(new File(sitesDir(), site), f -> f.isFile() && EDITABLE.matcher(f.getName()).matches());
    }

    private List<String> list(File dir, java.io.FileFilter filter) {
        List<String> names = new ArrayList<>();
        File[] files = dir.listFiles(filter);
        if (files != null) {
            for (File f : files) {
                names.add(f.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private void refreshSites(String select) {
        List<String> names = sites();
        siteSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        if (select != null) {
            siteSpinner.setSelection(names.indexOf(select));
        }
        // A spinner stays silent when the position does not change, even with new contents.
        siteChanged();
    }

    private void siteChanged() {
        save();
        currentFile = null;
        String site = (String) siteSpinner.getSelectedItem();
        showPublished(site == null ? null
                : getPreferences(MODE_PRIVATE).getString("url_" + site, null));
        refreshPages(INDEX);
    }

    private void refreshPages(String select) {
        String site = (String) siteSpinner.getSelectedItem();
        List<String> names = site == null ? new ArrayList<>() : pages(site);
        pageSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        pageSpinner.setSelection(Math.max(names.indexOf(select), 0));
        pageChanged();
    }

    private void pageChanged() {
        String site = (String) siteSpinner.getSelectedItem();
        String page = (String) pageSpinner.getSelectedItem();
        if (site == null || page == null) {
            return;
        }
        File selected = new File(new File(sitesDir(), site), page);
        if (selected.equals(currentFile)) {
            return; // the spinner reporting a page already open must not reload it
        }
        save();
        currentFile = selected;
        try {
            editor.setText(new String(vault.readAll(currentFile), StandardCharsets.UTF_8));
        } catch (IOException e) {
            currentFile = null; // never overwrite a page that could not be read
            editor.setText("");
            message.setText(e.getMessage());
        }
    }

    private boolean save() {
        if (currentFile == null) {
            return false;
        }
        try {
            vault.writeAll(currentFile, editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            message.setText(e.getMessage());
            return false;
        }
    }

    private boolean createSite(String name) {
        File dir = new File(sitesDir(), name);
        try {
            Files.createDirectories(dir.toPath());
            vault.writeAll(new File(dir, INDEX), TEMPLATE.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            message.setText(e.getMessage());
            return false;
        }
    }

    private void askName(int title, Pattern valid, String suffix, java.util.function.Consumer<String> done) {
        EditText input = new EditText(this);
        input.setHint(R.string.sites_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim().toLowerCase() + suffix;
                    if (valid.matcher(name).matches()) {
                        done.accept(name);
                    } else {
                        message.setText(R.string.sites_bad_name);
                    }
                })
                .show();
    }

    private void askNewSite() {
        askName(R.string.sites_new_site, SITE_NAME, "", name -> {
            if (sites().contains(name)) {
                message.setText(R.string.sites_exists);
            } else if (createSite(name)) {
                refreshSites(name);
            }
        });
    }

    private void askNewPage() {
        String site = (String) siteSpinner.getSelectedItem();
        if (site == null) {
            return;
        }
        askName(R.string.sites_new_page, PAGE_NAME, ".html", name -> {
            File file = new File(new File(sitesDir(), site), name);
            if (file.exists()) {
                message.setText(R.string.sites_exists);
                return;
            }
            try {
                save();
                vault.writeAll(file, TEMPLATE.getBytes(StandardCharsets.UTF_8));
                currentFile = null;
                refreshPages(name);
            } catch (IOException e) {
                message.setText(e.getMessage());
            }
        });
    }

    private void importFolder(Uri tree) {
        if (tree == null) {
            return;
        }
        DocumentFile source = DocumentFile.fromTreeUri(this, tree);
        if (source == null || source.findFile(INDEX) == null) {
            message.setText(R.string.sites_no_index);
            return;
        }
        String clean = String.valueOf(source.getName()).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        String base = clean.isEmpty() ? "sito" : clean.substring(0, Math.min(clean.length(), 34));
        String name = base;
        for (int n = 2; sites().contains(name); n++) {
            name = base + "-" + n;
        }
        String site = name;
        message.setText(R.string.sites_importing);
        new Thread(() -> {
            File target = new File(sitesDir(), site);
            try {
                copyTree(source, target, new long[]{0});
                runOnUiThread(() -> {
                    save();
                    refreshSites(site);
                    message.setText(getString(R.string.sites_imported, site));
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> message.setText(e.getMessage()));
            }
        }, "freenet-import").start();
    }

    private void copyTree(DocumentFile from, File to, long[] total) throws IOException {
        Files.createDirectories(to.toPath());
        for (DocumentFile child : from.listFiles()) {
            String childName = child.getName();
            // A folder provider chooses these names: none may step out of the site's folder.
            if (childName == null || childName.isEmpty() || childName.contains("/")
                    || childName.equals(".") || childName.equals("..")) {
                continue;
            }
            File target = new File(to, childName);
            if (child.isDirectory()) {
                copyTree(child, target, total);
            } else {
                try (InputStream in = getContentResolver().openInputStream(child.getUri());
                     OutputStream out = new FileOutputStream(target)) {
                    if (in == null) {
                        throw new IOException("Cannot read " + childName);
                    }
                    total[0] += Math.max(child.length(), 0);
                    if (total[0] > MAX_IMPORT_BYTES) {
                        throw new IOException(getString(R.string.sites_too_large));
                    }
                    vault.encrypt(in, out);
                }
            }
        }
    }

    private void preview() {
        WebView web = new WebView(this);
        // Only what is written in the page: no scripts, nothing from the internet.
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setBlockNetworkLoads(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.loadDataWithBaseURL(null, editor.getText().toString(), "text/html", "utf-8", null);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sites_preview)
                .setView(web)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void askPublish() {
        if (!save()) {
            return;
        }
        if (!Manager.getInstance().isRunning()) {
            message.setText(R.string.sites_node_off);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sites_publish)
                .setMessage(R.string.sites_publish_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sites_publish, (d, w) -> publish())
                .show();
    }

    private void publish() {
        String site = (String) siteSpinner.getSelectedItem();
        if (site == null) {
            return;
        }
        publishButton.setEnabled(false);
        showPublished(null);
        message.setText(R.string.sites_publishing);
        File dir = new File(sitesDir(), site);
        new Thread(() -> {
            String output;
            try {
                output = runPublish(site, dir);
            } catch (IOException | InterruptedException e) {
                output = String.valueOf(e.getMessage());
            }
            String result = output;
            Matcher url = SITE_URL.matcher(result);
            boolean published = result.contains("successfully") && url.find();
            runOnUiThread(() -> {
                publishButton.setEnabled(true);
                if (published) {
                    message.setText(getString(R.string.sites_published, url.group()));
                    getPreferences(MODE_PRIVATE).edit().putString("url_" + site, url.group()).apply();
                    showPublished(url.group());
                    copy(url.group());
                } else {
                    message.setText(getString(R.string.sites_publish_failed, lastLine(result)));
                }
            });
        }, "freenet-publish").start();
    }

    private String runPublish(String site, File dir) throws IOException, InterruptedException {
        // fdev only reads plain files: they exist in the private cache just for this moment.
        File plain = new File(getCacheDir(), "publish/" + site);
        File key = new File(Manager.configHome(this), "freenet/website-keys/" + site + ".toml");
        Vault.deleteTree(plain);
        try {
            vault.decryptTree(dir, plain);
            if (key.exists()) {
                vault.writePlain(key);
            } else {
                run(Arrays.asList(fdev(), "website", "init", site));
            }
            return run(Arrays.asList(fdev(), "website", "publish", plain.getAbsolutePath(), "--key", site,
                    "--timeout", String.valueOf(PUBLISH_TIMEOUT_SECONDS)));
        } finally {
            Vault.deleteTree(plain);
            if (key.exists()) {
                vault.writeAll(key, vault.readAll(key));
            }
        }
    }

    private String fdev() {
        return getApplicationInfo().nativeLibraryDir + "/libfdev.so";
    }

    private String run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        Manager.applyEnvironment(this, builder.environment());
        builder.environment().put("MODE", "network");
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
        if (!process.waitFor(PUBLISH_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return output.toString();
    }

    private String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        return lines[lines.length - 1];
    }

    private void showPublished(String url) {
        publishedUrl = url;
        openButton.setVisibility(url == null ? View.GONE : View.VISIBLE);
        copyButton.setVisibility(url == null ? View.GONE : View.VISIBLE);
    }

    private void copy(String url) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Freenet", url));
    }

    private void openPublished() {
        Intent intent = new Intent(this, BootstrapActivity.class);
        intent.putExtra(BootstrapActivity.EXTRA_URL, publishedUrl);
        startActivity(intent);
    }

    /** Calls back when a spinner changes, including its first selection. */
    private static class Selected implements AdapterView.OnItemSelectedListener {
        private final Runnable action;

        Selected(Runnable action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            action.run();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
