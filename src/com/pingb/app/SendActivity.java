package com.pingb.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pingb.app.ble.BleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 发送页：完整版 BLE 连接 + 发送（移植 PixDotDot 逻辑）
 * - 扫描过滤 PDD_ 设备，显示格式名（含尺寸）
 * - 连接握手：解析硬件信息自动识别板尺寸
 * - 15 秒连接超时 + 自动重连（记住 MAC）
 * - 发送：进入 DIY 模式(清屏) → RGB 数据 → 退出模式
 */
public class SendActivity extends Activity {

    private static final String TAG = "SendActivity";
    private static final int REQ_PERM = 100;
    private static final long CONNECT_TIMEOUT_MS = 15000;

    private static final String PREFS = "pingb_prefs";
    private static final String KEY_LAST_MAC = "last_device_mac";

    private BleManager bleManager;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final List<String> deviceNames = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView boardInfoText;
    private LinearLayout deviceListContent;
    private Button scanButton;
    private Button sendButton;
    private ProgressBar progressBar;
    private TextView progressText;

    private int gridN, gridM;
    private int[] gridColors;
    private String[] gridIds;
    private byte[] gridRgb;

    private BluetoothDevice selectedDevice;
    private boolean connecting;
    private boolean connected;
    private boolean destroyed;
    private int boardSize = 104; // 默认 104，握手后更新
    private int ledType = 3;      // 默认 104 板

    // 色号筛选（单色显示）状态：开启的色号集合
    private final java.util.Set<String> activeHighlightIds = new java.util.HashSet<>();
    private LinearLayout highlightBar;
    private TextView highlightHint;
    private final java.util.List<Button> highlightButtons = new ArrayList<>();
    private java.util.Map<String, Integer> highlightIdToColor = new java.util.HashMap<>();
    private boolean highlightSending;

    /**
     * 深色/黑色 MARD 色号（感知亮度 < 90）：
     * 智能拼豆板底色为黑，这些深色色号单色显示时看不清，
     * 点击时以绿色高亮点显示。按亮度升序。
     */
    private static final String[] DARK_CODES = {
            "H07", "H16", "D10", "H06", "B22", "C18", "D04", "C12", "D15", // 档1: 亮度<50
            "F11", "B23", "F08", "D21", "F07", "D22", "B09", "E13", "F15", "B15",
            "H05", "D13", "G08", "C29", "F05", "C08", "C16", "B12", "M12", "B21",
            "F06", "C09", "D14", "D03", "F10"  // 档2: 亮度 50-90
    };
    private static final java.util.Set<String> DARK_SET =
            new java.util.HashSet<>(java.util.Arrays.asList(DARK_CODES));

    /** 判断色号是否属于深色组（兼容带/不带前导零，如 H07 与 H7） */
    private static boolean isDarkCode(String id) {
        if (id == null) return false;
        String norm = normalizeCode(id);
        if (DARK_SET.contains(norm)) return true;
        // 尝试补前导零（字母+1位数字 → 字母+2位数字）
        if (norm.length() == 2 && Character.isLetter(norm.charAt(0)) && Character.isDigit(norm.charAt(1))) {
            return DARK_SET.contains(norm.substring(0, 1) + "0" + norm.substring(1));
        }
        return false;
    }

    /** 归一化色号：去空格、大写 */
    private static String normalizeCode(String id) {
        String s = id.trim().toUpperCase();
        return s;
    }

    private final Runnable connectTimeoutRunnable = () -> {
        if (connecting) {
            connecting = false;
            statusText.setText("状态: 连接超时 (15秒)");
            sendButton.setEnabled(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        gridN = getIntent().getIntExtra("grid_n", 0);
        gridM = getIntent().getIntExtra("grid_m", 0);
        gridColors = getIntent().getIntArrayExtra("grid_colors");
        gridIds = getIntent().getStringArrayExtra("grid_ids");
        gridRgb = getIntent().getByteArrayExtra("grid_rgb");

        bleManager = new BleManager(this);
        // 设备信息握手回调：获取真实板尺寸
        bleManager.setDeviceInfoListener(new BleManager.DeviceInfoListener() {
            @Override
            public void onHardwareInfo(int ledTypeFromBoard) {
                runOnUiThread(() -> {
                    int size = sizeForLedType(ledTypeFromBoard);
                    if (size > 0) {
                        boardSize = size;
                        ledType = ledTypeFromBoard;
                        boardInfoText.setText("拼豆板: 已连接  板尺寸: " + size + "x" + size
                                + "  (设备信息 ledType=" + ledTypeFromBoard + ")");
                        statusText.setText("状态: 已连接 ✓  发送前请确认图纸尺寸匹配");
                    }
                });
            }

            @Override
            public void onMcuVersion(String version) {
                runOnUiThread(() -> statusText.setText("状态: 已连接 ✓  固件 v" + version
                        + "  发送前请确认图纸尺寸匹配"));
            }
        });

        buildUi();
        checkPermissions();
        // 权限就绪后自动扫描（在 onRequestPermissionsResult 中触发）
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("连接拼豆板并发送");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        TextView gridInfo = new TextView(this);
        if (gridN > 0 && gridM > 0) {
            gridInfo.setText("图纸: " + gridN + "x" + gridM
                    + "  色号数: " + countUniqueColors(gridIds));
        } else {
            gridInfo.setText("（暂无图纸，可从识别页进入发送）");
        }
        gridInfo.setTextSize(14);
        gridInfo.setPadding(0, 0, 0, 8);
        root.addView(gridInfo);

        boardInfoText = new TextView(this);
        boardInfoText.setText("拼豆板: 未连接（默认 104x104）");
        boardInfoText.setTextSize(14);
        boardInfoText.setPadding(0, 0, 0, 16);
        root.addView(boardInfoText);

        // 色号筛选区（单色显示）：位于设备信息下方，5 列网格 + 垂直滚动
        highlightHint = new TextView(this);
        highlightHint.setText("色号筛选: 发送图纸后可用");
        highlightHint.setTextSize(13);
        highlightHint.setPadding(0, 0, 0, 4);
        root.addView(highlightHint);

        ScrollView highlightScroll = new ScrollView(this);
        highlightBar = new LinearLayout(this);
        highlightBar.setOrientation(LinearLayout.VERTICAL);
        highlightScroll.addView(highlightBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(highlightScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 240));

        // 图纸网格预览（仅在有图纸时显示）
        if (gridN > 0 && gridM > 0) {
            ImageView preview = new ImageView(this);
            preview.setPadding(0, 0, 0, 16);
            Bitmap previewBmp = renderPreview(gridN, gridM, gridColors);
            if (previewBmp != null) {
                preview.setImageDrawable(new BitmapDrawable(getResources(), previewBmp));
            }
            root.addView(preview, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 300));
        }

        statusText = new TextView(this);
        statusText.setText("状态: 正在扫描...");
        statusText.setTextSize(14);
        statusText.setPadding(0, 0, 0, 16);
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

        sendButton = new Button(this);
        sendButton.setText("发送图纸");
        sendButton.setEnabled(false);
        sendButton.setOnClickListener(v -> startSend());
        root.addView(sendButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progressText = new TextView(this);
        progressText.setText("");
        progressText.setPadding(0, 8, 0, 0);
        root.addView(progressText);

        setContentView(root);
    }

    private int countUniqueColors(String[] ids) {
        if (ids == null) return 0;
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String id : ids) {
            if (id != null && !id.isEmpty()) set.add(id);
        }
        return set.size();
    }

    /**
     * 原版 centerBitmapOnTargetBoard：图纸 RGB 居中填充到板子完整尺寸。
     * 周边像素为透明黑 (0,0,0)——与 createBitmap.eraseColor(0) + drawBitmap 一致。
     * 图纸超过板尺寸时：原版直接原样返回（不缩放不裁切），由板子自行处理。
     */
    private byte[] padToBoardSize(byte[] rgb, int n, int m, int boardSize) {
        if (rgb == null || n <= 0 || m <= 0) return rgb;
        // 原版：图比板大或相等 → 原样返回
        if (n >= boardSize || m >= boardSize) {
            return rgb;
        }
        byte[] out = new byte[boardSize * boardSize * 3]; // 全 0 = 透明黑
        int offX = (boardSize - n) / 2;
        int offY = (boardSize - m) / 2;
        for (int row = 0; row < m; row++) {
            System.arraycopy(rgb, row * n * 3,
                    out, ((offY + row) * boardSize + offX) * 3, n * 3);
        }
        return out;
    }

    private Bitmap renderPreview(int n, int m, int[] colors) {
        if (n <= 0 || m <= 0 || colors == null) return null;
        int cell = 6;
        Bitmap bmp = Bitmap.createBitmap(n * cell, m * cell, Bitmap.Config.ARGB_8888);
        for (int row = 0; row < m; row++) {
            for (int col = 0; col < n; col++) {
                int c = colors[row * n + col];
                for (int dy = 0; dy < cell; dy++) {
                    for (int dx = 0; dx < cell; dx++) {
                        bmp.setPixel(col * cell + dx, row * cell + dy, c);
                    }
                }
            }
        }
        return bmp;
    }

    // ==================== 扫描（PDD 过滤 + 自动重连） ====================

    private void startScan() {
        if (destroyed) return;
        // 关键修复：扫描前先断开所有遗留连接——板子被连接占用后停止广播，扫不到
        if (bleManager.isConnected() || connecting) {
            statusText.setText("状态: 断开旧连接，重新扫描...");
            bleManager.disconnect();
        }
        devices.clear();
        deviceNames.clear();
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
                    // 过滤拼豆板设备（PDD_ 前缀）
                    String name = device.getName();
                    if (name == null || !name.startsWith("PDD_")) {
                        return;
                    }
                    addDevice(device, name, rssi);
                    // 自动重连：如果该设备是上次连接的
                    autoReconnect(device);
                });
            }

            @Override
            public void onScanFinished() {
                runOnUiThread(() -> {
                    // 已连接时不要覆盖"已连接"状态
                    if (!connected && !connecting) {
                        statusText.setText("状态: 扫描完成，点击设备连接");
                    }
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

        String display = formatDeviceName(rawName);
        deviceNames.add(display);

        Button b = new Button(this);
        b.setText(display + "\n" + device.getAddress() + "  (" + rssi + "dBm)");
        b.setOnClickListener(v -> connectDevice(device));
        deviceListContent.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** 设备名格式化：PDD_104*104_xxx → 显示名称（含尺寸） */
    private String formatDeviceName(String rawName) {
        // PDD_29*29_x / PDD_32*32_x / PDD_52*52_x / PDD_78*78_x / PDD_104*104_x
        String name = rawName;
        if (name.startsWith("PDD_")) {
            String rest = name.substring(4);
            int idx = rest.indexOf('*');
            if (idx > 0) {
                int end = rest.indexOf('_', idx);
                if (end > 0) {
                    String size = rest.substring(0, end);
                    String suffix = rest.substring(end + 1);
                    return "拼豆板 " + size + " (" + suffix + ")";
                }
            }
        }
        return name;
    }

    /** 解析设备名中的板尺寸（PDD_104*104_xxx → 104） */
    private int parseBoardSizeFromName(String rawName) {
        if (rawName != null && rawName.startsWith("PDD_")) {
            String rest = rawName.substring(4);
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

    private void autoReconnect(BluetoothDevice device) {
        if (connecting || connected) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String lastMac = sp.getString(KEY_LAST_MAC, "");
        if (!lastMac.isEmpty() && lastMac.equals(device.getAddress())) {
            statusText.setText("状态: 自动重连上次设备...");
            connectDevice(device);
        }
    }

    // ==================== 连接（含握手） ====================

    private void connectDevice(BluetoothDevice device) {
        if (connecting) return;
        // 连接前停止扫描，避免扫描结果干扰状态显示
        bleManager.stopScan();
        selectedDevice = device;
        connecting = true;
        connected = false;
        sendButton.setEnabled(false);
        statusText.setText("状态: 连接 " + formatDeviceName(device.getName() != null ? device.getName() : "") + "...");

        // 启动 15 秒超时
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);

        bleManager.connect(device, new BleManager.ConnectListener() {
            @Override
            public void onConnected(BluetoothGatt gatt) {
                runOnUiThread(() -> {
                    handler.removeCallbacks(connectTimeoutRunnable);
                    connecting = false;
                    connected = true;
                    // 记住 MAC 用于自动重连
                    SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                    sp.edit().putString(KEY_LAST_MAC, device.getAddress()).apply();
                    // 从设备名识别板尺寸（PDD_104*104_xxx 格式）
                    int size = parseBoardSizeFromName(device.getName());
                    if (size > 0) {
                        boardSize = size;
                        updateLedTypeBySize(size);
                    }
                    boardInfoText.setText("拼豆板: 已连接 " + formatDeviceName(
                            device.getName() != null ? device.getName() : "")
                            + "  板尺寸: " + boardSize + "x" + boardSize);
                    statusText.setText("状态: 已连接 ✓  正在读取设备信息...");
                    sendButton.setEnabled(true);
                    // 原版连接后立即 setSwitchScreen(1) 开屏，然后串行握手：
                    // setSwitchScreen → getLedType(带时间) → getDeviceInfo
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
                    if (destroyed) return;
                    boardInfoText.setText("拼豆板: 未连接");
                    statusText.setText("状态: 已断开");
                    sendButton.setEnabled(false);
                    // 触发重新扫描以便自动重连
                    handler.postDelayed(SendActivity.this::startScan, 2000);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    connecting = false;
                    handler.removeCallbacks(connectTimeoutRunnable);
                    statusText.setText("状态: " + message);
                    sendButton.setEnabled(false);
                });
            }
        });
    }

    private void updateLedTypeBySize(int size) {
        switch (size) {
            case 29: ledType = 4; break;
            case 32: ledType = 0; break;
            case 52: ledType = 1; break;
            case 78: ledType = 2; break;
            default: ledType = 3; break; // 104
        }
    }

    /** ledType → 板尺寸（PixDotDot: 0=32, 1=52, 2=78, 3=104, 4=29） */
    private int sizeForLedType(int type) {
        switch (type) {
            case 0: return 32;
            case 1: return 52;
            case 2: return 78;
            case 3: return 104;
            case 4: return 29;
            default: return 0;
        }
    }

    // ==================== 发送（PixDotDot 完整流程） ====================

    private void startSend() {
        if (gridRgb == null || gridRgb.length == 0) {
            Toast.makeText(this, "还没有图纸数据（请先从识别页进入）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bleManager.isConnected()) {
            Toast.makeText(this, "请先连接拼豆板", Toast.LENGTH_SHORT).show();
            return;
        }
        // 检查图纸尺寸 vs 板尺寸——仅提示，不阻止（用户可测试板子对超尺寸数据的反应）
        if (Math.max(gridN, gridM) > boardSize) {
            Toast.makeText(this, "提示: 图纸 " + gridN + "x" + gridM + " 超过板尺寸 " + boardSize + "x"
                    + boardSize + "，将按原始尺寸发送测试板子反应", Toast.LENGTH_LONG).show();
        }

        sendButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressText.setText("准备发送...");

        // 1) 进入 DIY 模式（清屏）——原版 setDiyFunMode(ENTER_CLEAR_CUR_SHOW=1) = {5,0,4,1,1}
        bleManager.sendCommand(5, 0, 4, 1, 1, new BleManager.SendListener() {
            @Override
            public void onProgress(int sent, int total) {
            }

            @Override
            public void onComplete() {
                progressText.setText("已进入拼豆模式，发送图纸数据...");
                sendRgbData();
            }

            @Override
            public void onFail(String message) {
                progressText.setText("进入模式失败: " + message);
                sendButton.setEnabled(true);
            }
        });
    }

    private void sendRgbData() {
        // 2) 关键：原版 centerBitmapOnTargetBoard——图纸居中填充到板子完整尺寸后再发
        // （否则长度不匹配，板子回 ACK 04 拒绝）
        byte[] sendData = padToBoardSize(gridRgb, gridN, gridM, boardSize);
        // 3) 发送图纸 RGB 数据（原版 sendImageData: cmdType=1, 帧长按 ledType）
        bleManager.sendFrames(sendData, 1, ledType, false, new BleManager.SendListener() {
            @Override
            public void onProgress(int sent, int total) {
                runOnUiThread(() -> {
                    progressBar.setProgress(sent * 100 / Math.max(total, 1));
                    progressText.setText("发送图纸: " + sent + "/" + total + " 帧");
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    progressText.setText("图纸发送完成 ✓");
                    sendButton.setEnabled(true);
                    Toast.makeText(SendActivity.this, "图纸已发送到拼豆板", Toast.LENGTH_LONG).show();
                    // 保持 DIY 模式（原版编辑页一直处于 ENTER_CLEAR 模式），
                    // 这样后续色号筛选可直接发帧，不再退出到 QUIT_STILL
                    // 发送成功后构建色号筛选按钮
                    buildHighlightBar();
                });
            }

            @Override
            public void onFail(String message) {
                runOnUiThread(() -> {
                    progressText.setText("发送失败: " + message);
                    sendButton.setEnabled(true);
                });
            }
        });
    }

    // ==================== 色号筛选（单色显示，对齐原版 highlightedColorId 功能） ====================

    /** 图纸发送成功后构建色号按钮条（每行 5 个，按色块数从大到小排列） */
    private void buildHighlightBar() {
        activeHighlightIds.clear();
        highlightButtons.clear();
        highlightBar.removeAllViews();
        if (gridIds == null || gridColors == null) {
            highlightHint.setText("色号筛选: 无图纸数据");
            return;
        }
        // 统计每个色号的出现次数和颜色
        java.util.Map<String, Integer> idCount = new java.util.HashMap<>();
        java.util.Map<String, Integer> idColor = new java.util.HashMap<>();
        for (int i = 0; i < gridIds.length; i++) {
            String id = gridIds[i];
            if (id == null || id.isEmpty()) continue;
            Integer c = idCount.get(id);
            idCount.put(id, (c == null ? 0 : c) + 1);
            if (!idColor.containsKey(id)) {
                idColor.put(id, gridColors[i]);
            }
        }
        if (idCount.isEmpty()) {
            highlightHint.setText("色号筛选: 图纸无色号");
            return;
        }
        highlightIdToColor = idColor;
        // 按色块数降序排列；深色色号单独分组（亮度<90，黑板上看不清）
        java.util.List<String> sortedIds = new ArrayList<>(idCount.keySet());
        java.util.Collections.sort(sortedIds, (a, b) -> {
            int cmp = idCount.get(b).compareTo(idCount.get(a));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        java.util.List<String> normalIds = new ArrayList<>();
        java.util.List<String> darkIds = new ArrayList<>();
        for (String id : sortedIds) {
            if (isDarkCode(id)) {
                darkIds.add(id);
            } else {
                normalIds.add(id);
            }
        }
        highlightHint.setText("色号筛选 (" + sortedIds.size() + " 色, 按数量降序): 点击点亮/关闭对应色号位置（可多选）");

        // 普通色号区
        addButtonRows(highlightBar, normalIds, idCount, idColor);
        // 深色色号区（单列一组 + 解释）
        if (!darkIds.isEmpty()) {
            TextView darkTitle = new TextView(this);
            darkTitle.setText("深色色号（黑板上较暗）");
            darkTitle.setTextSize(13);
            darkTitle.setTextColor(0xFF66BB6A);
            darkTitle.setPadding(4, 12, 0, 2);
            highlightBar.addView(darkTitle);

            TextView darkExplain = new TextView(this);
            darkExplain.setText("颜色色号较深，建议每次点击一个按钮，此时该深色色号会变成绿色高亮点位显示在拼豆板上");
            darkExplain.setTextSize(11);
            darkExplain.setTextColor(0xFF999999);
            darkExplain.setPadding(4, 0, 0, 4);
            highlightBar.addView(darkExplain);

            addButtonRows(highlightBar, darkIds, idCount, idColor);
        }
    }

    /** 每行 5 个按钮（等宽）添加到容器 */
    private void addButtonRows(LinearLayout container, java.util.List<String> ids,
                               java.util.Map<String, Integer> idCount,
                               java.util.Map<String, Integer> idColor) {
        LinearLayout row = null;
        for (int k = 0; k < ids.size(); k++) {
            if (k % 5 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final String id = ids.get(k);
            final Button b = new Button(this);
            int color = idColor.get(id);
            b.setText(id + "\n×" + idCount.get(id));
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setPadding(0, 8, 0, 8);
            // 按钮底色显示色号颜色（深色文字保证可读）
            b.setBackgroundColor(color | 0xFF000000);
            int lum = ((color >> 16 & 0xFF) * 299 + (color >> 8 & 0xFF) * 587 + (color & 0xFF) * 114) / 1000;
            b.setTextColor(lum < 140 ? 0xFFFFFFFF : 0xFF000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(3, 3, 3, 3);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> toggleHighlight(id, b));
            highlightButtons.add(b);
            row.addView(b);
        }
    }

    /** 点击色号：开启/关闭该色号并发送到板子 */
    private void toggleHighlight(String id, Button btn) {
        if (!connected || !bleManager.isConnected()) {
            Toast.makeText(this, "请先连接拼豆板", Toast.LENGTH_SHORT).show();
            return;
        }
        if (highlightSending) {
            Toast.makeText(this, "正在发送，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean nowActive = !activeHighlightIds.contains(id);
        if (nowActive) {
            activeHighlightIds.add(id);
            btn.setText("● " + id + "\n×" + countBeads(id));
            btn.setPadding(0, 8, 0, 8);
        } else {
            activeHighlightIds.remove(id);
            Integer cnt = countBeads(id);
            btn.setText(id + "\n×" + cnt);
        }
        statusText.setText("状态: 色号筛选 " + activeHighlightIds.size() + " 个开启...");
        sendHighlightSelection();
    }

    private int countBeads(String id) {
        int c = 0;
        for (String gid : gridIds) {
            if (id.equals(gid)) c++;
        }
        return c;
    }

    /**
     * 发送当前开启色号集合到板子。
     * 主路径：{9,1} 坐标帧（原版 sendDiyImageData，持久提交型，ledType 2/3 专用）。
     * 多色号：首色号 option=0（新画面），后续色号 option=2（追加），测试板子叠加支持。
     * 注意：不能用 {0,0} 图片帧——那是非 2/3 板的 fallback，板子会几秒后超时恢复。
     */
    private void sendHighlightSelection() {
        if (activeHighlightIds.isEmpty()) {
            // 全部关闭 → 恢复完整图纸（先进入 DIY 清屏模式）
            progressBar.setVisibility(View.VISIBLE);
            progressText.setText("恢复完整图纸...");
            bleManager.sendCommand(5, 0, 4, 1, 1, new BleManager.SendListener() {
                @Override
                public void onProgress(int sent, int total) {
                }

                @Override
                public void onComplete() {
                    byte[] sendData = padToBoardSize(gridRgb, gridN, gridM, boardSize);
                    bleManager.sendFullImage(sendData, ledType, new BleManager.SendListener() {
                        @Override
                        public void onProgress(int sent, int total) {
                            runOnUiThread(() -> progressBar.setProgress(sent * 100 / Math.max(total, 1)));
                        }

                        @Override
                        public void onComplete() {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                progressText.setText("已恢复完整图纸 ✓");
                                statusText.setText("状态: 已连接 ✓");
                                highlightSending = false;
                            });
                        }

                        @Override
                        public void onFail(String message) {
                            runOnUiThread(() -> {
                                progressText.setText("恢复失败: " + message);
                                statusText.setText("状态: " + message);
                                highlightSending = false;
                            });
                        }
                    });
                }

                @Override
                public void onFail(String message) {
                    runOnUiThread(() -> {
                        progressText.setText("进入模式失败: " + message);
                        statusText.setText("状态: " + message);
                        highlightSending = false;
                    });
                }
            });
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        progressText.setText("发送色号筛选...");
        // 先进入 DIY 模式（不清屏），保持板子接收状态
        bleManager.sendCommand(5, 0, 4, 1, 3, new BleManager.SendListener() {
            @Override
            public void onProgress(int sent, int total) {
            }

            @Override
            public void onComplete() {
                // 收集开启色号的坐标点（板坐标），逐色号发送 {9,1} 坐标帧
                java.util.List<String> ids = new ArrayList<>(activeHighlightIds);
                java.util.Collections.sort(ids);
                final int offX = Math.max((boardSize - gridN) / 2, 0);
                final int offY = Math.max((boardSize - gridM) / 2, 0);
                sendHighlightFrames(ids, 0, offX, offY);
            }

            @Override
            public void onFail(String message) {
                runOnUiThread(() -> {
                    progressText.setText("进入模式失败: " + message);
                    statusText.setText("状态: " + message);
                    highlightSending = false;
                });
            }
        });
    }

    /** 逐色号发送 {9,1} 坐标帧：首色号 option=0，后续 option=2（追加叠加） */
    private void sendHighlightFrames(final java.util.List<String> ids, final int idx, final int offX, final int offY) {
        if (idx >= ids.size()) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                progressText.setText("色号筛选已更新 ✓");
                statusText.setText("状态: 已连接 ✓  " + activeHighlightIds.size() + " 色点亮");
                highlightSending = false;
            });
            return;
        }
        String id = ids.get(idx);
        // 收集该色号的所有格子（板坐标）
        java.io.ByteArrayOutputStream pts = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < gridIds.length; i++) {
            if (id.equals(gridIds[i])) {
                int col = i % gridN;
                int row = i / gridN;
                pts.write(col + offX);
                pts.write(row + offY);
            }
        }
        byte[] points = pts.toByteArray();
        if (points.length == 0) {
            sendHighlightFrames(ids, idx + 1, offX, offY);
            return;
        }
        Integer colorInt = highlightIdToColor.get(id);
        int c = colorInt != null ? colorInt : 0xFFFFFF;
        // 深色色号优化：黑板上看不清 → 用绿色高亮显示
        if (isDarkCode(id)) {
            c = 0x00FF00;
        }
        byte[] rgb = new byte[]{(byte) (c >> 16 & 0xFF), (byte) (c >> 8 & 0xFF), (byte) (c & 0xFF)};
        final int option = idx == 0 ? 0 : 2; // 首帧 0，追加帧 2
        final int nextIdx = idx + 1;
        bleManager.sendDiyHighlight(rgb, points, option, () -> runOnUiThread(() -> {
            handler.postDelayed(() -> sendHighlightFrames(ids, nextIdx, offX, offY), 120);
        }));
    }

    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERM);
        } else {
            // 权限已就绪，直接开始扫描
            startScan();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            // 用户授权后开始扫描；如果拒绝也尝试扫描（有的系统允许部分扫描）
            startScan();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(connectTimeoutRunnable);
        if (bleManager != null) bleManager.disconnect();
        super.onDestroy();
    }
}
