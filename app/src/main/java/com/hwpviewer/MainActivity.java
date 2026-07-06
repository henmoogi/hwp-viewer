package com.hwpviewer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 한글 뷰어 — 오프라인 WebView 앱.
 * viewer.html(assets)을 로드하고, 탭/공유로 들어온 .hwp/.hwpx 파일을
 * 네이티브에서 읽어 JS 브릿지(window.Android)로 전달한다.
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Bridge bridge = new Bridge();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // 로컬 파일(file://)에서 같은 폴더 리소스 로드 허용
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        web.addJavascriptInterface(bridge, "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // 내부 페이지만 사용
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                // 페이지 로드 완료 후 대기 중인 파일이 있으면 불러오게 트리거
                v.evaluateJavascript("window.__loadPending && window.__loadPending();", null);
            }
        });

        // 들어온 파일 처리
        handleIntent(getIntent());

        web.loadUrl("file:///android_asset/viewer.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        if (web != null) {
            web.evaluateJavascript("window.__loadPending && window.__loadPending();", null);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;

        try {
            String name = queryName(uri);
            byte[] data = readAll(uri);
            if (data.length > 40 * 1024 * 1024) {
                Toast.makeText(this, "파일이 너무 큽니다(40MB 초과)", Toast.LENGTH_LONG).show();
                return;
            }
            bridge.set(name, Base64.encodeToString(data, Base64.NO_WRAP));
        } catch (Exception e) {
            Toast.makeText(this, "파일을 읽지 못했습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private String queryName(Uri uri) {
        String name = "문서.hwp";
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) {
                    String dn = c.getString(idx);
                    if (dn != null && !dn.isEmpty()) name = dn;
                }
                c.close();
            }
        } catch (Exception ignored) {}
        if (name.equals("문서.hwp")) {
            String last = uri.getLastPathSegment();
            if (last != null && (last.toLowerCase().endsWith(".hwp") || last.toLowerCase().endsWith(".hwpx"))) {
                name = last.substring(last.lastIndexOf('/') + 1);
            }
        }
        return name;
    }

    /** JS 브릿지: 페이지가 window.Android.getData()/getName()으로 파일을 가져감 */
    public static class Bridge {
        private String name = null;
        private String base64 = null;
        void set(String n, String b64) { this.name = n; this.base64 = b64; }
        @JavascriptInterface public boolean hasFile() { return base64 != null; }
        @JavascriptInterface public String getName() { return name == null ? "" : name; }
        @JavascriptInterface public String getData() {
            String d = base64;
            base64 = null; name = null; // 1회 소비
            return d == null ? "" : d;
        }
    }
}
