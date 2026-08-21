package com.pingb.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 识别页：WebView 加载 PinGB 静态页面
 * 用户在 PinGB 中完成：导入图片 → 像素化/逆向识别 → 编辑 → 点"发送到拼豆板"
 * 数据经 AndroidBridge 回传，处理后进入发送页。
 */
public class RecognizeActivity extends Activity {

    private static final String TAG = "RecognizeActivity";
    private static final String PAGE_URL = "file:///android_asset/pingb/index.html";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button backButton;

    /** 把选中的图片缓存（用于 onShowFileChooser 直接返回） */
    private Uri selectedUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 读取传入图片 URI
        String uriStr = getIntent().getStringExtra("image_uri");
        if (uriStr != null) {
            try {
                selectedUri = Uri.parse(uriStr);
            } catch (Exception e) {
                selectedUri = null;
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶部栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(24, 24, 24, 12);

        backButton = new Button(this);
        backButton.setText("返回");
        topBar.addView(backButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setText("在 PinGB 中完成识别后，点击「发送到拼豆板」");
        statusText.setPadding(24, 0, 0, 0);
        topBar.addView(statusText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        root.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // WebView 容器
        FrameLayout webContainer = new FrameLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);

        webContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 10);
        webContainer.addView(progressBar, pbLp);

        root.addView(webContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        setupWebView();
        backButton.setOnClickListener(v -> finish());
    }

    @SuppressWarnings("deprecation")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // PinGB 里点"上传图片"时，直接返回主界面已选好的图片（无需二次选择）
                if (selectedUri != null) {
                    filePathCallback.onReceiveValue(new Uri[]{selectedUri});
                    return true;
                }
                // 没有预选图片：走系统相册
                chooserCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(intent, REQ_CHOOSER);
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                // PinGB 静态页的资源是绝对路径（/_next/... /qrcode.jpg），
                // 在 file:// 下会解析到文件根，这里把它们映射到 assets/pingb/
                return mapAssetResource(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return mapAssetResource(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });

        progressBar.setVisibility(View.VISIBLE);
        webView.loadUrl(PAGE_URL);
    }

    /** 把绝对路径资源映射到 assets/pingb/ 下 */
    private WebResourceResponse mapAssetResource(String url) {
        try {
            String path = null;
            if (url.startsWith("file:///android_asset/pingb/")) {
                path = url.substring("file:///android_asset/pingb/".length());
            } else if (url.startsWith("file:///android_asset/")) {
                path = url.substring("file:///android_asset/".length());
            } else if (url.startsWith("/")) {
                path = url.substring(1); // /_next/... → _next/...
            } else if (url.startsWith("file:///_next/")) {
                path = url.substring("file:///".length());
            }
            if (path == null || path.isEmpty()) return null;

            // 防止路径穿越
            String clean = path.replace("..", "");
            java.io.InputStream is = getAssets().open("pingb/" + clean);
            String mime = guessMime(clean);
            return new WebResourceResponse(mime, "UTF-8", is);
        } catch (Exception e) {
            // 资源不存在则交给 WebView 默认处理（404）
            return null;
        }
    }

    private String guessMime(String path) {
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static final int REQ_CHOOSER = 2001;
    private ValueCallback<Uri[]> chooserCallback;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHOOSER) {
            if (chooserCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                chooserCallback.onReceiveValue(result);
                chooserCallback = null;
            }
        }
    }

    /** 把选中的图片缓存为文件（保留备用） */
    private void cacheSelectedImage(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return;
            byte[] bytes = readAll(is);
            is.close();
            File tmp = new File(getCacheDir(), "import_image_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(bytes);
            fos.close();
            Log.i(TAG, "image cached to " + tmp.getAbsolutePath() + " (" + bytes.length + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "cacheSelectedImage error", e);
        }
    }

    private byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    /** JS 桥：接收 PinGB 的"发送到拼豆板"数据 */
    private class AndroidBridge {
        @JavascriptInterface
        public void sendBeads(String json) {
            Log.d(TAG, "sendBeads: " + json);
            runOnUiThread(() -> {
                try {
                    DataConverter.GridData grid = DataConverter.parse(json);
                    // 把数据传给发送页
                    Intent intent = new Intent(RecognizeActivity.this, SendActivity.class);
                    intent.putExtra("grid_n", grid.n);
                    intent.putExtra("grid_m", grid.m);
                    intent.putExtra("grid_colors", grid.colors);
                    intent.putExtra("grid_ids", grid.ids);
                    intent.putExtra("grid_rgb", grid.rgbData);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "parse failed", e);
                    Toast.makeText(RecognizeActivity.this,
                            "解析图纸数据失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
