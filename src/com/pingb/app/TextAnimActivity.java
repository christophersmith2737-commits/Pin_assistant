package com.pingb.app;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pingb.app.ble.BleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 文字滚动动画：把输入的文字渲染成位图，逐帧截取窗口，
 * 用 {9,1} 高亮坐标帧发送到拼豆板，形成从左到右滚动播放效果。
 */
public class TextAnimActivity extends Activity {

    private static final String TAG = "TextAnimActivity";
    private static final int REQ_PERM = 101;

    private BleManager bleManager;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView boardInfoText;
    private LinearLayout deviceListContent;
    private Button scanButton;
    private Button startButton;
    private Button stopButton;
    private ProgressBar progressBar;
    private EditText textInput;
    private EditText heightInput;
    private EditText speedInput;

    private BluetoothDevice selectedDevice;
    private boolean connecting;
    private boolean connected;
    private boolean destroyed;
    private int boardSize = 104;
    private int ledType = 3;

    private volatile boolean animRunning;
    private int animToken;
    private byte[] animTextColor = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}; // 默认白字
    // 位图每列亮点行号缓存（流畅度优化：避免每帧逐像素扫描）
    private java.util.List<Integer>[] columnLights;

    private final Runnable connectTimeoutRunnable = () -> {
        if (connecting) {
            connecting = false;
            statusText.setText("状态: 连接超时 (15秒)");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bleManager = new BleManager(this);
        buildUi();
        checkPermissions();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("文字滚动动画");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        // 文字输入
        TextView label1 = new TextView(this);
        label1.setText("动画文字:");
        label1.setTextSize(14);
        root.addView(label1);
        textInput = new EditText(this);
        textInput.setHint("例如: 薛之谦我爱你");
        textInput.setTextSize(16);
        root.addView(textInput);

        // 高度（像素行数）
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView label2 = new TextView(this);
        label2.setText("字高:");
        label2.setTextSize(14);
        row1.addView(label2);
        heightInput = new EditText(this);
        heightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        heightInput.setText("100");
        heightInput.setTextSize(14);
        row1.addView(heightInput, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView label3 = new TextView(this);
        label3.setText("  倍速(1-8):");
        label3.setTextSize(14);
        row1.addView(label3);
        speedInput = new EditText(this);
        speedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        speedInput.setText("80");
        speedInput.setTextSize(14);
        row1.addView(speedInput, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        boardInfoText = new TextView(this);
        boardInfoText.setText("拼豆板: 未连接（默认 104x104）");
        boardInfoText.setTextSize(14);
        boardInfoText.setPadding(0, 8, 0, 8);
        root.addView(boardInfoText);

        statusText = new TextView(this);
        statusText.setText("状态: 正在扫描...");
        statusText.setTextSize(14);
        statusText.setPadding(0, 0, 0, 8);
        root.addView(statusText);

        scanButton = new Button(this);
        scanButton.setText("重新扫描");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView deviceList = new ScrollView(this);
        deviceListContent = new LinearLayout(this);
        deviceListContent.setOrientation(LinearLayout.VERTICAL);
        deviceList.addView(deviceListContent);
        root.addView(deviceList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("▶ 开始滚动");
        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> startAnimation());
        row2.addView(startButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        stopButton = new Button(this);
        stopButton.setText("■ 停止");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopAnimation());
        row2.addView(stopButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("提示: 倍速 = 每帧滚动像素数（1-8），帧间隔固定 150ms 以保证板子处理得过来。\n"
                + "动画循环播放，按停止结束。");
        hint.setTextSize(12);
        hint.setPadding(0, 8, 0, 0);
        root.addView(hint);

        setContentView(root);
    }

    // ==================== 扫描/连接 ====================

    private void startScan() {
        devices.clear();
        deviceListContent.removeAllViews();
        scanButton.setEnabled(false);
        statusText.setText("状态: 扫描中...");
        if (!bleManager.isEnabled()) {
            statusText.setText("状态: 请开启蓝牙");
            scanButton.setEnabled(true);
            return;
        }
        bleManager.startScan(new BleManager.ScanListener() {
            @Override
            public void onDeviceFound(BluetoothDevice device, int rssi) {
                runOnUiThread(() -> {
                    String name = device.getName();
                    if (name == null || !name.startsWith("PDD_")) return;
                    addDevice(device, name, rssi);
                });
            }

            @Override
            public void onScanFinished() {
                runOnUiThread(() -> {
                    statusText.setText("状态: 扫描完成，点击设备连接");
                    scanButton.setEnabled(true);
                });
            }
        }, 10000);
    }

    private void addDevice(BluetoothDevice device, String rawName, int rssi) {
        for (BluetoothDevice d : devices) {
            if (d.getAddress().equals(device.getAddress())) return;
        }
        devices.add(device);
        Button b = new Button(this);
        b.setText(rawName + "\n" + device.getAddress() + "  (" + rssi + "dBm)");
        b.setOnClickListener(v -> connectDevice(device));
        deviceListContent.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void connectDevice(BluetoothDevice device) {
        if (connecting) return;
        bleManager.stopScan();
        selectedDevice = device;
        connecting = true;
        statusText.setText("状态: 连接 " + device.getName() + "...");
        handler.postDelayed(connectTimeoutRunnable, 15000);
        bleManager.connect(device, new BleManager.ConnectListener() {
            @Override
            public void onConnected(BluetoothGatt gatt) {
                runOnUiThread(() -> {
                    handler.removeCallbacks(connectTimeoutRunnable);
                    connecting = false;
                    connected = true;
                    int size = parseBoardSize(device.getName());
                    if (size > 0) {
                        boardSize = size;
                        updateLedType(size);
                    }
                    boardInfoText.setText("拼豆板: 已连接 " + device.getName()
                            + "  板尺寸: " + boardSize + "x" + boardSize);
                    statusText.setText("状态: 已连接 ✓  可开始动画");
                    startButton.setEnabled(true);
                    // 完整握手（与 SendActivity 一致）：
                    // setSwitchScreen(1) 开屏 → getLedType → getDeviceInfo
                    bleManager.sendCommand(5, 0, 7, 1, 1, new BleManager.SendListener() {
                        @Override
                        public void onProgress(int sent, int total) {
                        }

                        @Override
                        public void onComplete() {
                            bleManager.sendLedTypeCommand(() -> {
                                if (connected && !destroyed) {
                                    bleManager.requestDeviceInfo(null);
                                }
                            });
                        }

                        @Override
                        public void onFail(String message) {
                            // 开屏失败不阻塞握手
                            bleManager.sendLedTypeCommand(() -> {
                                if (connected && !destroyed) {
                                    bleManager.requestDeviceInfo(null);
                                }
                            });
                        }
                    });
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    connecting = false;
                    connected = false;
                    handler.removeCallbacks(connectTimeoutRunnable);
                    statusText.setText("状态: 已断开");
                    startButton.setEnabled(false);
                    stopButton.setEnabled(false);
                    handler.postDelayed(TextAnimActivity.this::startScan, 2000);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    connecting = false;
                    handler.removeCallbacks(connectTimeoutRunnable);
                    statusText.setText("状态: " + message);
                });
            }
        });
    }

    private int parseBoardSize(String name) {
        if (name != null && name.startsWith("PDD_")) {
            String rest = name.substring(4);
            int idx = rest.indexOf('*');
            if (idx > 0) {
                try {
                    return Integer.parseInt(rest.substring(0, idx));
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private void updateLedType(int size) {
        switch (size) {
            case 29: ledType = 4; break;
            case 32: ledType = 0; break;
            case 52: ledType = 1; break;
            case 78: ledType = 2; break;
            default: ledType = 3; break;
        }
    }

    // ==================== 动画 ====================

    private void startAnimation() {
        String text = textInput.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入文字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!connected || !bleManager.isConnected()) {
            Toast.makeText(this, "请先连接拼豆板", Toast.LENGTH_SHORT).show();
            return;
        }
        int height = 100;
        try {
            height = Integer.parseInt(heightInput.getText().toString().trim());
        } catch (Exception ignored) {
        }
        height = Math.max(10, Math.min(height, boardSize));
        int speedRaw = 1;
        try {
            speedRaw = Integer.parseInt(speedInput.getText().toString().trim());
        } catch (Exception ignored) {
        }
        final int step = Math.max(1, Math.min(speedRaw, 16)); // 每帧跳 N 像素（倍速，最高 16）
        final int interval = 70; // 帧间隔：预索引后发送更快，70ms 提升流畅度

        stopAnimation();
        animToken++;
        final int token = animToken;
        animRunning = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusText.setText("状态: 准备动画...");

        // 渲染文字位图（宽度自动，高度 = 指定高度，居中于板高）
        Bitmap textBmp = renderTextBitmap(text, boardSize, height);
        if (textBmp == null) {
            statusText.setText("状态: 文字渲染失败");
            animRunning = false;
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            return;
        }
        final int textW = textBmp.getWidth();
        Log.i(TAG, "text bitmap " + textW + "x" + boardSize);

        // 进入 DIY 模式：必须用 ENTER_CLEAR=1（清屏进入）——
        // ENTER_NO_CLEAR=3 只能在已是 DIY 模式时用，从普通显示模式进入会被板子忽略
        bleManager.sendCommand(5, 0, 4, 1, 1, new BleManager.SendListener() {
            @Override
            public void onProgress(int sent, int total) {
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (token != animToken || !animRunning) return;
                    statusText.setText("状态: 发送底色图...");
                    // 关键：{9,1} 高亮帧是"叠加"类型，且板子在纯黑底上会忽略高亮点。
                    // 发送深灰底图（非纯黑），白色文字叠加在深灰底上滚动。
                    byte[] base = new byte[boardSize * boardSize * 3];
                    for (int i = 0; i < base.length; i++) {
                        base[i] = (byte) 40; // RGB(40,40,40) 深灰
                    }
                    bleManager.sendFrames(base, 1, ledType, false, new BleManager.SendListener() {
                        @Override
                        public void onProgress(int sent, int total) {
                        }

                        @Override
                        public void onComplete() {
                            runOnUiThread(() -> {
                                if (token != animToken || !animRunning) return;
                                statusText.setText("状态: 切换叠加模式...");
                                // 关键（与色号筛选验证有效的命令链一致）：
                                // 发 {9,1} 高亮帧前必须先 setDiyFunMode(3) ENTER_NO_CLEAR
                                // 切换到叠加模式，否则板子忽略高亮帧
                                bleManager.sendCommand(5, 0, 4, 1, 3, new BleManager.SendListener() {
                                    @Override
                                    public void onProgress(int sent, int total) {
                                    }

                                    @Override
                                    public void onComplete() {
                                        runOnUiThread(() -> {
                                            if (token != animToken || !animRunning) return;
                                            statusText.setText("状态: 滚动播放中（循环）...");
                                            playFrame(textBmp, 0, interval, step, token);
                                        });
                                    }

                                    @Override
                                    public void onFail(String message) {
                                        runOnUiThread(() -> {
                                            statusText.setText("状态: 切换模式失败: " + message);
                                            animRunning = false;
                                            startButton.setEnabled(true);
                                            stopButton.setEnabled(false);
                                        });
                                    }
                                });
                            });
                        }

                        @Override
                        public void onFail(String message) {
                            runOnUiThread(() -> {
                                statusText.setText("状态: 底色图失败: " + message);
                                animRunning = false;
                                startButton.setEnabled(true);
                                stopButton.setEnabled(false);
                            });
                        }
                    });
                });
            }

            @Override
            public void onFail(String message) {
                runOnUiThread(() -> {
                    statusText.setText("状态: 进入模式失败: " + message);
                    animRunning = false;
                    startButton.setEnabled(true);
                    stopButton.setEnabled(false);
                });
            }
        });
    }

    private void stopAnimation() {
        animRunning = false;
        animToken++;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusText.setText("状态: 已连接 ✓  动画已停止");
    }

    /** 渲染文字为位图（高 = 指定高度，垂直居中于板） */
    private Bitmap renderTextBitmap(String text, int boardSize, int height) {
        try {
            // 先用一个临时画布测量文字宽度（普通字重 + 无抗锯齿，减少每帧点数）
            Paint measure = new Paint();
            measure.setTextSize(height * 0.92f);
            measure.setTypeface(android.graphics.Typeface.DEFAULT);
            float textWidth = measure.measureText(text);
            Log.i(TAG, "measureText width=" + textWidth + " for '" + text + "' h=" + height);
            int pad = 8;
            int w = (int) Math.ceil(textWidth) + pad * 2;
            if (w <= 0) return null;
            Bitmap bmp = Bitmap.createBitmap(w, boardSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.BLACK); // 黑底（不透明）
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);   // 白字
            paint.setTextSize(height * 0.92f);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = (boardSize - (fm.descent - fm.ascent)) / 2 - fm.ascent;
            canvas.drawText(text, pad, baseline, paint);
            // 诊断：统计非黑像素数
            int lit = 0;
            for (int y = 0; y < boardSize; y++) {
                for (int x = 0; x < w; x++) {
                    int c = bmp.getPixel(x, y);
                    if (((c >> 24) & 0xFF) > 0 && ((c >> 16) & 0xFF) > 40) lit++;
                }
            }
            Log.i(TAG, "renderTextBitmap " + w + "x" + boardSize + " lit=" + lit
                    + " textSize=" + paint.getTextSize() + " baseline=" + baseline);
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "renderTextBitmap failed", e);
            return null;
        }
    }

    /**
     * 逐帧播放：文字位图"拼接两份"形成无限循环（无缝），
     * 窗口在拼接图上从左往右移动 = 文字从左到右滚动。
     * 流畅度优化：位图每列的亮点行号预计算（columnLights），
     * 每帧只需拼接窗口内列的亮点，避免逐像素扫描。
     * 关键（对齐原版 splitByPointLimit）：每帧高亮数据按 2048 点分块，
     * 首块 option=0，后续块 option=2——超过 2048 点板子会忽略整帧！
     */
    private void playFrame(final Bitmap textBmp, final int frameIdx,
                           final int interval, final int step, final int token) {
        if (!animRunning || token != animToken || !connected) return;
        final int bw = textBmp.getWidth();   // 原文字宽度
        final int bh = textBmp.getHeight();
        // 窗口左缘在"拼接图"上的位置：从 0 递增到 textW，循环（窗口右移 = 文字从左到右滚动）
        int x0 = (frameIdx * step) % bw; // 拼接坐标（0..textW-1）
        // 预计算：每列的亮点行号（只算一次，缓存到字段）
        if (columnLights == null || columnLights.length != bw) {
            columnLights = new java.util.List[bw];
            for (int col = 0; col < bw; col++) {
                java.util.List<Integer> rows = new ArrayList<>();
                for (int row = 0; row < bh; row++) {
                    int px = textBmp.getPixel(col, row);
                    if (((px >> 24) & 0xFF) > 0 && ((px >> 16) & 0xFF) > 40
                            && ((px >> 8) & 0xFF) > 40 && (px & 0xFF) > 40) {
                        rows.add(row);
                    }
                }
                columnLights[col] = rows;
            }
            Log.i(TAG, "column lights built: " + bw + " cols");
        }
        // 拼接窗口内的亮点：窗口覆盖拼接列 [x0, x0+boardSize)，实际位图列 = c % bw
        java.io.ByteArrayOutputStream pts = new java.io.ByteArrayOutputStream();
        for (int wIdx = 0; wIdx < boardSize; wIdx++) {
            int col = ((x0 + wIdx) % bw + bw) % bw;
            java.util.List<Integer> rows = columnLights[col];
            if (rows == null || rows.isEmpty()) continue;
            for (Integer row : rows) {
                pts.write(wIdx); // 窗口内 x（0..boardSize-1）
                pts.write(row);
            }
        }
        byte[] points = pts.toByteArray();
        final int pointCount = points.length / 2;
        if (frameIdx % 20 == 0) {
            final int f = frameIdx;
            runOnUiThread(() -> {
                statusText.setText("状态: 滚动播放中 " + f
                        + " (帧点 " + pointCount + ")");
            });
        }
        // 分块发送：每块 ≤2048 点（原版 selectedBeadCoordinatePacketSize），首块 option=0 续块 option=2
        final java.util.List<byte[]> chunks = new ArrayList<>();
        int maxPoints = 2048;
        for (int off = 0; off < points.length; off += maxPoints * 2) {
            int len = Math.min(maxPoints * 2, points.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(points, off, chunk, 0, len);
            chunks.add(chunk);
        }
        sendHighlightChunks(textBmp, frameIdx, chunks, 0, interval, step, token);
    }

    /** 串行发送一个画面的所有分块（首块 0 / 续块 2），完成后进入下一帧 */
    private void sendHighlightChunks(final Bitmap textBmp, final int frameIdx,
                                     final java.util.List<byte[]> chunks, final int idx,
                                     final int interval, final int step, final int token) {
        if (!animRunning || token != animToken || !connected) return;
        if (idx >= chunks.size()) {
            // 本帧所有分块发完 → 下一帧（缩短帧间隔提升流畅度）
            handler.postDelayed(() -> playFrame(textBmp, frameIdx + 1,
                    interval, step, token), interval);
            return;
        }
        int option = idx == 0 ? 0 : 2; // 首块 0，续块 2（原版 index == 0 ? 0 : 2）
        bleManager.sendDiyHighlight(animTextColor, chunks.get(idx), option, () -> {
            if (!animRunning || token != animToken) return;
            // 块间延迟尽量短（原版 100ms，这里用 30ms 提升连续感）
            handler.postDelayed(() -> sendHighlightChunks(textBmp, frameIdx, chunks, idx + 1,
                    interval, step, token), 30);
        });
    }

    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERM);
        } else {
            startScan();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            startScan();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        animRunning = false;
        handler.removeCallbacks(connectTimeoutRunnable);
        if (bleManager != null) bleManager.disconnect();
        super.onDestroy();
    }
}
