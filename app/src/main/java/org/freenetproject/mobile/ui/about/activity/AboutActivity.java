package org.freenetproject.mobile.ui.about.activity;

import android.os.Bundle;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.R;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        WebView wv = (WebView) findViewById(R.id.about_webview);
        wv.loadData(
                getResources().getString(
                        R.string.text_about,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.FREENET_VERSION
                ),
                "text/html; charset=utf-8",
                null
        );
    }
}
