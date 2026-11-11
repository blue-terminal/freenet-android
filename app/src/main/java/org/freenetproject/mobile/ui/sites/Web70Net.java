package org.freenetproject.mobile.ui.sites;

import android.content.Context;
import android.util.Base64;

import org.freenetproject.mobile.services.node.Manager;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the Web 7.0 pages do on the Freenet network: publish sites and use the shared hub contract. */
public final class Web70Net {
    private static final Pattern SITE = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,80}");
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9:_-]{1,120}");
    private static final Pattern CONTRACT_ID = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,50}");
    private static final Pattern SITE_URL = Pattern.compile("/v1/contract/web/([1-9A-HJ-NP-Za-km-z]+)/");
    private static final long MAX_SITE_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_MESSAGE_BYTES = 16 * 1024;
    private static final int HUB_TIMEOUT_SECONDS = 60;
    private static final int PUBLISH_TIMEOUT_SECONDS = 600;
    private static final String HUB_ASSET = "web70_hub.contract";

    private final Context context;
    private final Vault vault;
    private final Map<String, String> topicIds = new ConcurrentHashMap<>();

    public Web70Net(Context context) throws IOException {
        this.context = context.getApplicationContext();
        this.vault = new Vault(this.context);
    }

    /** Runs one request from a page and answers with JSON: {"ok":true,...} or {"ok":false,"error":...}. */
    public String handle(String method, String arguments) {
        try {
            JSONObject args = new JSONObject(arguments);
            JSONObject reply = new JSONObject();
            switch (method) {
                case "siteWrite":
                    siteWrite(args.getString("site"), args.getString("file"), args.getString("b64"),
                            args.optBoolean("append"));
                    break;
                case "siteRead":
                    reply.put("text", siteRead(args.getString("site"), args.getString("file")));
                    break;
                case "siteDeleteFile":
                    siteDeleteFile(args.getString("site"), args.getString("file"));
                    break;
                case "siteDelete":
                    Vault.deleteTree(siteDir(args.getString("site")));
                    break;
                case "sitePublish":
                    reply.put("key", sitePublish(args.getString("site")));
                    break;
                case "pagesPublish":
                    reply.put("key", pagesPublish());
                    break;
                case "hubGet":
                    reply.put("state", hubGet(args.getString("topic")));
                    break;
                case "hubPost":
                    hubPost(args.getString("topic"), args.getString("message"));
                    break;
                default:
                    throw new IOException("Unknown request");
            }
            return reply.put("ok", true).toString();
        } catch (IOException | JSONException | IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"Interrotto\"}";
        }
    }

    private File siteDir(String site) throws IOException {
        if (!SITE.matcher(site).matches()) {
            throw new IOException("Invalid site name");
        }
        return new File(context.getFilesDir(), "web70/sites/" + site);
    }

    private File siteFile(String site, String file) throws IOException {
        if (!FILE.matcher(file).matches() || file.contains("..")) {
            throw new IOException("Invalid file name");
        }
        return new File(siteDir(site), file);
    }

    private void siteWrite(String site, String file, String b64, boolean append) throws IOException {
        File target = siteFile(site, file);
        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
        if ((append ? target.length() : 0) + bytes.length > MAX_SITE_FILE_BYTES) {
            throw new IOException("File too large");
        }
        Files.createDirectories(target.getParentFile().toPath());
        try (FileOutputStream out = new FileOutputStream(target, append)) {
            out.write(bytes);
        }
    }

    private String siteRead(String site, String file) throws IOException {
        File target = siteFile(site, file);
        return target.isFile() ? new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8) : "";
    }

    private void siteDeleteFile(String site, String file) throws IOException {
        Files.deleteIfExists(siteFile(site, file).toPath());
    }

    private String sitePublish(String site) throws IOException, InterruptedException {
        File dir = siteDir(site);
        if (!new File(dir, "index.html").isFile()) {
            throw new IOException("index.html is missing");
        }
        File key = new File(Manager.configHome(context), "freenet/website-keys/" + site + ".toml");
        try {
            if (key.exists()) {
                vault.writePlain(key);
            } else {
                run(Arrays.asList(fdev(), "website", "init", site), HUB_TIMEOUT_SECONDS);
            }
            String output = "";
            for (int attempt = 0; attempt < 3; attempt++) {
                output = run(Arrays.asList(fdev(), "website", "publish", dir.getAbsolutePath(), "--key", site,
                        "--timeout", String.valueOf(PUBLISH_TIMEOUT_SECONDS)), PUBLISH_TIMEOUT_SECONDS + 30);
                // The site version is the time in seconds: two publishes in one second are refused.
                if (!output.contains("must be higher")) {
                    break;
                }
                Thread.sleep(1200);
            }
            Matcher url = SITE_URL.matcher(output);
            if (output.contains("successfully") && url.find()) {
                return url.group(1);
            }
            throw new IOException(lastLine(output));
        } finally {
            if (key.exists()) {
                vault.writeAll(key, vault.readAll(key));
            }
        }
    }

    private static final String PAGES_SITE = "w70-pagine";
    private static final String[] PAGE_FILES = {
        "index.html", "video.html", "photos.html", "posts.html", "chess.html", "search.html",
        "fn-api.js", "fn-ws.js", "fn-net.js", "social.js", "chess-engine.js", "web7.css", "fn-theme.css",
        "web70_hub.contract", "web70_client.wasm",
    };

    /** Publishes the app's own pages as a Freenet site, once per app version, and returns its key. */
    private String pagesPublish() throws IOException, InterruptedException {
        android.content.SharedPreferences prefs = context.getSharedPreferences("web70", Context.MODE_PRIVATE);
        long version = org.freenetproject.mobile.BuildConfig.VERSION_CODE;
        String known = prefs.getString("pages_key", null);
        if (known != null && prefs.getLong("pages_version", 0) == version) {
            return known;
        }
        File dir = siteDir(PAGES_SITE);
        Files.createDirectories(dir.toPath());
        for (String name : PAGE_FILES) {
            try (InputStream in = context.getAssets().open(name)) {
                Files.write(new File(dir, name).toPath(), readFully(in));
            }
        }
        String key = sitePublish(PAGES_SITE);
        prefs.edit().putString("pages_key", key).putLong("pages_version", version).apply();
        return key;
    }

    private File hubCode() throws IOException {
        File code = new File(context.getFilesDir(), "web70/" + HUB_ASSET);
        try (InputStream in = context.getAssets().open(HUB_ASSET)) {
            byte[] bytes = readFully(in);
            if (!code.isFile() || !Arrays.equals(bytes, Files.readAllBytes(code.toPath()))) {
                Files.createDirectories(code.getParentFile().toPath());
                Files.write(code.toPath(), bytes);
            }
        }
        return code;
    }

    private static byte[] readFully(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private File topicParameters(String topic) throws IOException {
        if (!TOPIC.matcher(topic).matches()) {
            throw new IOException("Invalid topic");
        }
        File file = File.createTempFile("topic", ".bin", context.getCacheDir());
        Files.write(file.toPath(), topic.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String topicId(String topic) throws IOException, InterruptedException {
        String known = topicIds.get(topic);
        if (known != null) {
            return known;
        }
        File parameters = topicParameters(topic);
        try {
            String output = run(Arrays.asList(fdev(), "get-contract-id", "--code", hubCode().getAbsolutePath(),
                    "--parameters", parameters.getAbsolutePath()), HUB_TIMEOUT_SECONDS);
            Matcher id = CONTRACT_ID.matcher(output);
            String found = null;
            while (id.find()) {
                found = id.group();
            }
            if (found == null) {
                throw new IOException(lastLine(output));
            }
            topicIds.put(topic, found);
            return found;
        } finally {
            Files.deleteIfExists(parameters.toPath());
        }
    }

    private String hubGet(String topic) throws IOException, InterruptedException {
        File state = File.createTempFile("state", ".json", context.getCacheDir());
        try {
            String output = run(Arrays.asList(fdev(), "execute", "get", topicId(topic), "-o", state.getAbsolutePath(),
                    "--timeout", String.valueOf(HUB_TIMEOUT_SECONDS)), HUB_TIMEOUT_SECONDS + 10);
            if (output.contains("missing contract")) {
                return "{\"m\":[]}";
            }
            if (output.contains("Error:")) {
                throw new IOException(lastLine(output));
            }
            return new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(state.toPath());
        }
    }

    private void hubPost(String topic, String message) throws IOException, InterruptedException {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        if (text.length > MAX_MESSAGE_BYTES) {
            throw new IOException("Message too large");
        }
        File state = File.createTempFile("delta", ".json", context.getCacheDir());
        File parameters = topicParameters(topic);
        try {
            Files.write(state.toPath(), ("{\"m\":[" + message + "]}").getBytes(StandardCharsets.UTF_8));
            String output = run(Arrays.asList(fdev(), "execute", "update", topicId(topic), state.getAbsolutePath(),
                    "--timeout", String.valueOf(HUB_TIMEOUT_SECONDS)), HUB_TIMEOUT_SECONDS + 10);
            if (!output.contains("Error:")) {
                return;
            }
            // The first message of a topic creates its contract; a PUT over an existing one merges.
            output = run(Arrays.asList(fdev(), "execute", "put", "--code", hubCode().getAbsolutePath(),
                    "--parameters", parameters.getAbsolutePath(), "--subscribe",
                    "--timeout", String.valueOf(HUB_TIMEOUT_SECONDS),
                    "contract", "--state", state.getAbsolutePath()), HUB_TIMEOUT_SECONDS + 10);
            if (output.contains("Error:")) {
                throw new IOException(lastLine(output));
            }
        } finally {
            Files.deleteIfExists(state.toPath());
            Files.deleteIfExists(parameters.toPath());
        }
    }

    private String fdev() {
        return context.getApplicationInfo().nativeLibraryDir + "/libfdev.so";
    }

    private String run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        File log = File.createTempFile("fdev", ".log", context.getCacheDir());
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            Manager.applyEnvironment(context, builder.environment());
            builder.environment().put("MODE", "network");
            builder.redirectErrorStream(true);
            builder.redirectOutput(log);
            Process process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timeout: the node did not answer");
            }
            String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            if (output.trim().isEmpty() && process.exitValue() != 0) {
                throw new IOException("fdev stopped with code " + process.exitValue());
            }
            return output;
        } finally {
            Files.deleteIfExists(log.toPath());
        }
    }

    private String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        return lines[lines.length - 1].replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
