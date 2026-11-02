package org.freenetproject.mobile.ui.about.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.R;

public class AboutActivity extends AppCompatActivity {
    private static final String PAGE_STYLE =
            "<style>body{color:#D5DAE1;font-family:sans-serif;line-height:1.5}a{color:#60A5FA}</style>";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        WebView wv = (WebView) findViewById(R.id.about_webview);
        wv.setBackgroundColor(Color.TRANSPARENT);
        // Not loadData: it would cut the page at the first # of the colors.
        wv.loadDataWithBaseURL(null,
                PAGE_STYLE + getResources().getString(
                        R.string.text_about,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.FREENET_VERSION
                ),
                "text/html", "utf-8", null
        );
    }
}
