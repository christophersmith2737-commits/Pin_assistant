package com.pingb.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 主界面：选择图片 → 进入 PinGB 识别页 → 发送页（BLE）
 * 也支持：导入 PinGB 网页下载的图纸 JSON 文件 → 直接进入发送页
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_IMPORT_FILE = 1002;

    private TextView infoText;
    private Button recognizeButton;
    private Uri selectedUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("PinGB 拼豆助手");
        title.setTextSize(26);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("PinGB 识别 + PixDotDot 蓝牙协议");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, 0, 0, 32);
        root.addView(subtitle);

        Button selectButton = new Button(this);
        selectButton.setText("选择图片");
        selectButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
        });
        root.addView(selectButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        infoText = new TextView(this);
        infoText.setText("请先选择一张图片");
        infoText.setPadding(0, 16, 0, 0);
        root.addView(infoText);

        recognizeButton = new Button(this);
        recognizeButton.setText("开始识别 (PinGB)");
        recognizeButton.setEnabled(false);
        recognizeButton.setOnClickListener(v -> {
            if (selectedUri == null) {
                Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, RecognizeActivity.class);
            intent.putExtra("image_uri", selectedUri.toString());
            startActivity(intent);
        });
        root.addView(recognizeButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 连接拼豆板入口（独立于识别，可先连接设备）
        Button connectButton = new Button(this);
        connectButton.setText("连接拼豆板 (BLE)");
        connectButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SendActivity.class);
            startActivity(intent);
        });
        root.addView(connectButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 文字滚动动画入口
        Button animButton = new Button(this);
        animButton.setText("文字滚动动画");
        animButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, TextAnimActivity.class);
            startActivity(intent);
        });
        root.addView(animButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 导入图纸文件（PinGB 网页下载的 JSON）
        Button importButton = new Button(this);
        importButton.setText("导入图纸文件");
        importButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            // 默认打开 Download 目录（避免停在"最近"标签只显示图片，找不到 json）
            try {
                Uri downloadUri = Uri.parse(
                        "content://com.android.externalstorage.documents/document/primary%3ADownload");
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadUri);
            } catch (Exception ignored) {
            }
            startActivityForResult(intent, REQ_IMPORT_FILE);
        });
        root.addView(importButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tip = new TextView(this);
        tip.setText("流程：选图 → 识别 → 发送\n或先连接拼豆板再识别发送");
        tip.setTextSize(12);
        tip.setPadding(0, 16, 0, 0);
        root.addView(tip);

        setContentView(root);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                selectedUri = uri;
                infoText.setText("已选择图片 ✓");
                recognizeButton.setEnabled(true);
            }
        } else if (requestCode == REQ_IMPORT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                importBeadFile(uri);
            }
        }
    }

    /** 读取 PinGB 下载的图纸 JSON，解析后进入发送页 */
    private void importBeadFile(Uri uri) {
        String json;
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show();
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            json = sb.toString();
            // 去掉 UTF-8 BOM（某些传输/编辑器会给文件加 BOM，org.json 不认）
            if (json.length() > 0 && json.charAt(0) == '\uFEFF') {
                json = json.substring(1);
            }
        } catch (Exception e) {
            Toast.makeText(this, "读取文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DataConverter.GridData grid = DataConverter.parse(json);
            if (grid.n <= 0 || grid.m <= 0) {
                Toast.makeText(this, "图纸数据无效", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, SendActivity.class);
            intent.putExtra("grid_n", grid.n);
            intent.putExtra("grid_m", grid.m);
            intent.putExtra("grid_colors", grid.colors);
            intent.putExtra("grid_ids", grid.ids);
            intent.putExtra("grid_rgb", grid.rgbData);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "图纸解析失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
