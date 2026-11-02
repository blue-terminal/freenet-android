package org.freenetproject.mobile.ui.sites;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A video site, hosted on this phone and published to Freenet like any other site here.
 * The page is static: a Freenet site cannot take uploads, so the videos are the ones added
 * from this phone. Every one of them is written to videos.json, which the page reads, and
 * to a catalog kept on the phone, so there is a record of what was published and under
 * which name.
 */
final class VideoSite {
    static final String LIST = "videos.json";
    static final String FOLDER = "video";
    /** The catalog of everything published from this phone, encrypted with the site files. */
    static final String CATALOG = "catalog.json";

    private static final String PAGE = "<!DOCTYPE html>\n"
            + "<html lang=\"it\">\n<head>\n<meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            + "<title>%1$s</title>\n"
            + "<style>\n"
            + "*{box-sizing:border-box}\n"
            + "body{margin:0;background:#0B1F44;color:#E8EEF9;"
            + "font-family:system-ui,sans-serif;line-height:1.5}\n"
            + "header{padding:24px 16px;border-bottom:1px solid #4A6BA8}\n"
            + "h1{margin:0;font-size:1.5rem}\n"
            + "header p{margin:4px 0 0;color:#C3D2EC;font-size:.9rem}\n"
            + "main{display:grid;gap:20px;padding:20px 16px;max-width:900px;margin:0 auto;\n"
            + "grid-template-columns:repeat(auto-fill,minmax(280px,1fr))}\n"
            + ".card{background:#16336A;border:1px solid #4A6BA8;border-radius:10px;overflow:hidden}\n"
            + "video{width:100%%;display:block;background:#000}\n"
            + ".body{padding:12px}\n"
            + ".title{margin:0;font-size:1rem}\n"
            + ".meta{margin:4px 0 0;color:#C3D2EC;font-size:.85rem}\n"
            + ".empty{padding:20px 16px;color:#C3D2EC}\n"
            + "</style>\n</head>\n<body>\n"
            + "<header>\n<h1>%1$s</h1>\n<p>Video su Freenet</p>\n</header>\n"
            + "<main id=\"list\"></main>\n"
            + "<p class=\"empty\" id=\"empty\" hidden>Nessun video.</p>\n"
            + "<script>\n"
            + "fetch('videos.json').then(r => r.json()).then(videos => {\n"
            + "  const list = document.getElementById('list');\n"
            + "  if (!videos.length) { document.getElementById('empty').hidden = false; return; }\n"
            + "  for (const video of videos) {\n"
            + "    const card = document.createElement('article');\n"
            + "    card.className = 'card';\n"
            + "    const player = document.createElement('video');\n"
            + "    player.controls = true;\n"
            + "    player.preload = 'metadata';\n"
            + "    player.src = video.file;\n"
            + "    const body = document.createElement('div');\n"
            + "    body.className = 'body';\n"
            + "    const title = document.createElement('h2');\n"
            + "    title.className = 'title';\n"
            + "    title.textContent = video.title;\n"
            + "    const meta = document.createElement('p');\n"
            + "    meta.className = 'meta';\n"
            + "    meta.textContent = video.author + ' \\u00b7 ' + video.date;\n"
            + "    body.appendChild(title);\n"
            + "    body.appendChild(meta);\n"
            + "    card.appendChild(player);\n"
            + "    card.appendChild(body);\n"
            + "    list.appendChild(card);\n"
            + "  }\n"
            + "}).catch(() => { document.getElementById('empty').hidden = false; });\n"
            + "</script>\n</body>\n</html>\n";

    private VideoSite() {
    }

    static String page(String name) {
        return String.format(Locale.ROOT, PAGE, name);
    }

    /** Adds the video to the site's list and to the phone's catalog. */
    static void record(Vault vault, File siteDir, File catalog, String site,
                       String fileName, String title, String author)
            throws IOException, JSONException {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        JSONObject entry = new JSONObject();
        entry.put("file", FOLDER + "/" + fileName);
        entry.put("title", title);
        entry.put("author", author);
        entry.put("date", date);

        File list = new File(siteDir, LIST);
        JSONArray videos = read(vault, list);
        videos.put(entry);
        write(vault, list, videos);

        JSONObject record = new JSONObject(entry.toString());
        record.put("site", site);
        JSONArray all = read(vault, catalog);
        all.put(record);
        write(vault, catalog, all);
    }

    static JSONArray read(Vault vault, File file) throws IOException, JSONException {
        if (!file.isFile() || file.length() == 0) {
            return new JSONArray();
        }
        return new JSONArray(new String(vault.readAll(file), StandardCharsets.UTF_8));
    }

    private static void write(Vault vault, File file, JSONArray array)
            throws IOException, JSONException {
        vault.writeAll(file, array.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
