package com.pingb.app.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BLE 连接管理（参考 PixDotDot 的 MyBleManager）
 * 负责：扫描、连接、GATT 服务发现、特征写入。
 */
public class BleManager {

    private static final String TAG = "BleManager";

    // 拼豆板 GATT UUID（从 PixDotDot MyBleManager 还原）
    public static final UUID SERVICE_UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb");
    public static final UUID NOTIFY_UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb");

    public interface ScanListener {
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onScanFinished();
    }

    public interface ConnectListener {
        void onConnected(BluetoothGatt gatt);
        void onDisconnected();
        void onError(String message);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;

    // 跟踪所有创建的 GATT 实例，防止孤儿连接占用设备
    private final List<BluetoothGatt> allGatts = new CopyOnWriteArrayList<>();
    private boolean connecting;
    // 主动断开时抑制 onDisconnected 回调（避免触发重扫）
    private boolean suppressDisconnectCallback;
    private int currentMtu = 23; // 协商后更新（原版 requestMtu(512)）

    public interface DeviceInfoListener {
        void onHardwareInfo(int ledType);
        void onMcuVersion(String version);
    }

    private DeviceInfoListener deviceInfoListener;

    public void setDeviceInfoListener(DeviceInfoListener listener) {
        this.deviceInfoListener = listener;
    }

    private final List<ScanListener> scanListeners = new CopyOnWriteArrayList<>();
    private final List<ConnectListener> connectListeners = new CopyOnWriteArrayList<>();
    private final List<byte[]> pendingPackets = new CopyOnWriteArrayList<>();
    private boolean sending;
    private int sendIndex;

    public BleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm.getAdapter();
        if (this.adapter != null) {
            this.scanner = this.adapter.getBluetoothLeScanner();
        }
    }

    public boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    // ==================== 扫描 ====================

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            for (ScanListener l : scanListeners) {
                l.onDeviceFound(device, result.getRssi());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed: " + errorCode);
            for (ScanListener l : scanListeners) {
                l.onScanFinished();
            }
        }
    };

    @SuppressLint("MissingPermission")
    public void startScan(ScanListener listener, long durationMs) {
        scanListeners.add(listener);
        if (scanner == null) {
            listener.onScanFinished();
            return;
        }
        // 防止重复启动扫描（scan failed: 1）
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            Log.e(TAG, "startScan error", e);
            listener.onScanFinished();
            return;
        }
        handler.postDelayed(this::stopScan, durationMs);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        for (ScanListener l : scanListeners) {
            l.onScanFinished();
        }
        scanListeners.clear();
    }

    // ==================== 连接 ====================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "connected, discovering services");
                connecting = false;
                gatt = g;
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected");
                connecting = false;
                if (gatt == g) {
                    gatt = null;
                }
                allGatts.remove(g);
                try {
                    g.close();
                } catch (Exception ignored) {
                }
                writeChar = null;
                if (suppressDisconnectCallback) {
                    Log.i(TAG, "disconnect callback suppressed (manual)");
                    suppressDisconnectCallback = false;
                    return;
                }
                for (ConnectListener l : connectListeners) {
                    l.onDisconnected();
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false;
                for (ConnectListener l : connectListeners) {
                    l.onError("服务发现失败: " + status);
                }
                return;
            }
            // 找到可写的特征
            BluetoothGattCharacteristic write = findWritableCharacteristic(g);
            if (write == null) {
                // 打印所有服务/特征便于排查
                StringBuilder sb = new StringBuilder("available services:\n");
                for (BluetoothGattService s : g.getServices()) {
                    sb.append("  svc ").append(s.getUuid()).append("\n");
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        sb.append("    char ").append(c.getUuid())
                                .append(" props=0x").append(Integer.toHexString(c.getProperties())).append("\n");
                    }
                }
                Log.w(TAG, sb.toString());
                for (ConnectListener l : connectListeners) {
                    l.onError("未找到可写特征，请确认设备服务UUID");
                }
                return;
            }
            gatt = g;
            writeChar = write;
            initDescDone = false;
            initMtuDone = false;
            connectedNotified = false;
            // 订阅通知（收 ACK）——优先用 findWritableCharacteristic 选中的 notifyChar（原版逻辑），
            // 没有则退回 SERVICE_UUID 下的 ae02
            BluetoothGattCharacteristic notify = notifyChar;
            if (notify == null) {
                BluetoothGattService svc = g.getService(SERVICE_UUID);
                if (svc != null) {
                    notify = svc.getCharacteristic(NOTIFY_UUID);
                    if (notify == null) {
                        // 找第一个支持通知的
                        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                            if ((c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                    | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                                notify = c;
                                break;
                            }
                        }
                    }
                }
            }
            if (notify != null) {
                try {
                    g.setCharacteristicNotification(notify, true);
                    BluetoothGattDescriptor desc = notify.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (desc != null) {
                        // 关键：按特征属性选择 NOTIFY 或 INDICATE（原版 supportsNotify 逻辑）
                        int props = notify.getProperties();
                        boolean supportsNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                        boolean supportsIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                        if (supportsNotify) {
                            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            Log.i(TAG, "cccd write: ENABLE_NOTIFICATION (props=0x"
                                    + Integer.toHexString(props) + ")");
                        } else if (supportsIndicate) {
                            desc.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                            Log.i(TAG, "cccd write: ENABLE_INDICATION (props=0x"
                                    + Integer.toHexString(props) + ")");
                        }
                        g.writeDescriptor(desc);
                    } else {
                        Log.w(TAG, "CCCD descriptor not found, skip");
                        initDescDone = true;
                        maybeNotifyConnected();
                    }
                    notifyChar = notify;
                    Log.i(TAG, "notify subscribed: " + notify.getUuid());
                } catch (Exception e) {
                    Log.w(TAG, "notify subscribe failed", e);
                    initDescDone = true;
                    maybeNotifyConnected();
                }
            } else {
                Log.w(TAG, "no notify characteristic found");
                initDescDone = true;
                maybeNotifyConnected();
            }
            // 协商 MTU（原版 requestMtu(512)，长帧发送必需）——完成后回调 onMtuChanged
            try {
                Log.i(TAG, "requesting MTU 512");
                g.requestMtu(512);
            } catch (Exception e) {
                Log.w(TAG, "requestMtu failed", e);
                initMtuDone = true;
                maybeNotifyConnected();
            }
            // 注意：不再立即回调 onConnected；等 CCCD + MTU 都完成（严格串行）
        }

        /** CCCD 写入完成回调 */
        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "cccd descriptor written, status=" + status);
            initDescDone = true;
            maybeNotifyConnected();
        }

        /** CCCD 与 MTU 都完成后才通知上层（严格串行，避免写冲突） */
        private void maybeNotifyConnected() {
            if (connectedNotified) return;
            if (!initDescDone || !initMtuDone) return;
            if (gatt == null) return;
            connectedNotified = true;
            Log.i(TAG, "init complete (cccd + mtu), ready");
            for (ConnectListener l : connectListeners) {
                l.onConnected(gatt);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (commandSending) {
                    // 命令写完 → 执行回调 → 发下一条排队命令
                    commandSending = false;
                    Runnable done = commandDone;
                    commandDone = null;
                    if (done != null) done.run();
                    drainCommandQueue();
                    return;
                }
                onPacketWritten();
            } else {
                Log.e(TAG, "write failed: " + status);
                sending = false;
                if (commandSending) {
                    commandSending = false;
                    Runnable done = commandDone;
                    commandDone = null;
                    if (done != null) done.run(); // 失败也继续，避免卡死（发送方自行判断）
                    drainCommandQueue();
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu;
                Log.i(TAG, "mtu negotiated: " + mtu);
            } else {
                Log.w(TAG, "mtu negotiation failed: " + status + ", keep " + currentMtu);
            }
            initMtuDone = true;
            maybeNotifyConnected();
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) {
                handleNotify(value);
            }
        }
    };

    private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGatt g) {
        // 对齐原版 isRequiredServiceSupported：
        // 遍历所有服务，找第一个"同时包含 WriteNoResponse 特征 + NotifyOrIndicate 特征"的服务，
        // writeChar=该服务第一个 WriteNoResponse，notifyChar=该服务第一个 NotifyOrIndicate
        // 找不到则退而求其次：Write(with response) + NotifyOrIndicate
        StringBuilder sb = new StringBuilder("services dump:\n");
        for (BluetoothGattService s : g.getServices()) {
            sb.append("  svc ").append(s.getUuid()).append("\n");
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                sb.append("    char ").append(c.getUuid())
                        .append(" props=0x").append(Integer.toHexString(c.getProperties())).append("\n");
            }
        }
        Log.i(TAG, sb.toString());

        // 第一轮：WriteNoResponse + NotifyOrIndicate（原版优先）
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic write = null;
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    write = c;
                    break;
                }
            }
            if (write != null) {
                BluetoothGattCharacteristic notify = null;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if ((c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        notify = c;
                        break;
                    }
                }
                if (notify != null) {
                    this.notifyChar = notify;
                    Log.i(TAG, "writeChar=" + write.getUuid() + " notifyChar=" + notify.getUuid());
                    return write;
                }
            }
        }
        // 第二轮：Write(with response) + NotifyOrIndicate
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic write = null;
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    write = c;
                    break;
                }
            }
            if (write != null) {
                BluetoothGattCharacteristic notify = null;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if ((c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        notify = c;
                        break;
                    }
                }
                if (notify != null) {
                    this.notifyChar = notify;
                    Log.i(TAG, "writeChar=" + write.getUuid() + " notifyChar=" + notify.getUuid());
                    return write;
                }
            }
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, ConnectListener listener) {
        // 防重复连接：如果已有连接或正在连接，先断开旧的
        if (connecting) {
            Log.i(TAG, "connect ignored: already connecting");
            listener.onError("正在连接中，请稍候");
            return;
        }
        if (gatt != null) {
            Log.i(TAG, "closing previous gatt before reconnect");
            disconnect();
        }
        // 只保留最新 listener，避免断开时重复触发回调
        connectListeners.clear();
        connectListeners.add(listener);
        connecting = true;
        BluetoothGatt g = device.connectGatt(context, false, gattCallback);
        if (g != null) {
            allGatts.add(g);
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        suppressDisconnectCallback = true;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        // 关闭所有孤儿 GATT 连接
        for (BluetoothGatt g : allGatts) {
            if (g != null) {
                try {
                    g.disconnect();
                    g.close();
                } catch (Exception ignored) {
                }
            }
        }
        allGatts.clear();
        writeChar = null;
        notifyChar = null;
        sending = false;
        pendingPackets.clear();
        connecting = false;
        commandQueue.clear();
        commandSending = false;
        commandDone = null;
        initDescDone = false;
        initMtuDone = false;
        connectedNotified = false;
        diyHighlightMode = false;
        diyHighlightDone = null;
        // 若 suppress 标志未被回调消费（无活跃连接），2 秒后复位
        handler.postDelayed(() -> suppressDisconnectCallback = false, 2000);
    }

    public boolean isConnected() {
        return gatt != null && writeChar != null;
    }

    // ==================== 发送（PixDotDot 帧协议） ====================

    public interface SendListener {
        void onProgress(int sent, int total);
        void onComplete();
        void onFail(String message);
    }

    private SendListener sendListener;
    private int totalPackets;
    private int ackType;
    // 当前帧拆包状态
    private byte[] chunkedFrame;
    private int chunkIndex;
    private int chunkCount;
    // 初始化阶段：descriptor 写入 + MTU 协商都完成后才回调 onConnected（严格串行，对齐原版）
    private boolean initDescDone;
    private boolean initMtuDone;
    private boolean connectedNotified;
    // 命令队列：严格串行发送（原版 BleGattQueue 排队）
    private final java.util.ArrayDeque<Runnable> commandQueue = new java.util.ArrayDeque<>();
    private boolean commandSending;
    private Runnable commandDone;

    /**
     * 发送完整数据（自动分帧 + ACK 推进）——对齐原版 BaseSend.sendImageData
     * @param data        原始数据（图片 RGB 字节流）
     * @param cmdType     1=图纸（原版 cmdType=1，帧[14]=cmdType）
     * @param ledType     板子类型（决定帧长：0/1→12288, 2→8192, 3→4096）
     * @param isHighlight 高亮模式（原版 isHighlightClick=true）：
     *                    帧头 9 字节 {len, 0,0, option, totalLen}，无 CRC/serial；
     *                    ACK 为 {5,0,0,0,3}=下一帧 / {5,0,0,0,1}=完成
     * @param listener    回调
     */
    @SuppressLint("MissingPermission")
    public void sendFrames(byte[] data, int cmdType, int ledType, boolean isHighlight, SendListener listener) {
        if (!isConnected()) {
            listener.onFail("设备未连接");
            return;
        }
        sendListener = listener;
        // ACK 类型：高亮(摄像头式)=0，普通图片=2
        ackType = isHighlight ? 0 : 2;

        final int frameLen = frameLengthForLedType(ledType); // 原版 currentFrameLength()
        int frameCount = (data.length + frameLen - 1) / frameLen;
        totalPackets = frameCount;
        // 总 CRC：对完整数据计算（原版 CrcUtils.CRC32.CRC32(data, 0, data.length)）；高亮帧无 CRC
        int totalCrc = isHighlight ? 0 : Crc32.crc32(data, 0, data.length);

        pendingPackets.clear();
        for (int i = 0; i < frameCount; i++) {
            int off = i * frameLen;
            int len = Math.min(frameLen, data.length - off);
            byte[] payload = new byte[len];
            System.arraycopy(data, off, payload, 0, len);
            byte[] frame = buildFrame(payload, cmdType, data.length, totalCrc, i, isHighlight);
            pendingPackets.add(frame);
        }
        sendIndex = 0;
        sending = true;
        sendNextPacket();
    }

    /** 帧长选择：ledType 0/1→12288(12K), 2→8192(8K), 3→4096(4K)（原版 currentFrameLength） */
    private int frameLengthForLedType(int ledType) {
        if (ledType == 2) return 8192;
        if (ledType == 3) return 4096;
        return 12288;
    }

    /** 构造原版 payloadChannel 帧（type=2：普通图 15 字节头 / 高亮 9 字节头） */
    private byte[] buildFrame(byte[] payload, int cmdType, int totalLen, int totalCrc, int seq, boolean isHighlight) {
        if (isHighlight) {
            // 高亮帧：{len(2), 0,0(2), option(1), totalLen(4)} + data —— 无 CRC、无 serial
            int headerLen = 9;
            byte[] frame = new byte[headerLen + payload.length];
            int frameTotal = headerLen + payload.length;
            frame[0] = (byte) (frameTotal & 0xFF);
            frame[1] = (byte) ((frameTotal >> 8) & 0xFF);
            frame[2] = 0; // getDataType(2, true) = {0,0}（摄像头式）
            frame[3] = 0;
            frame[4] = (byte) (seq == 0 ? 0 : 2);
            // [5-8] 总数据长度 LE（原版 intToBytesLE(totalData.length)）
            System.arraycopy(Crc32.toLittleEndian(totalLen), 0, frame, 5, 4);
            System.arraycopy(payload, 0, frame, headerLen, payload.length);
            return frame;
        }
        int headerLen = 15;
        byte[] frame = new byte[headerLen + payload.length];
        // [0-1] 帧总长 LE = payload + 15（原版 length = data.length + 9 + 6）
        int frameTotal = headerLen + payload.length;
        frame[0] = (byte) (frameTotal & 0xFF);
        frame[1] = (byte) ((frameTotal >> 8) & 0xFF);
        // [2-3] 数据类型：原版 getDataType(2,false) = {2,0}
        frame[2] = 2;
        frame[3] = 0;
        // [4] option（首包 0 续包 2）
        frame[4] = (byte) (seq == 0 ? 0 : 2);
        // [5-8] 总数据长度 LE
        System.arraycopy(Crc32.toLittleEndian(totalLen), 0, frame, 5, 4);
        // [9-12] 总数据 CRC32 LE
        System.arraycopy(Crc32.toLittleEndian(totalCrc), 0, frame, 9, 4);
        // [13]=0, [14]=cmdType（原版 payloadChannel[14] = cmdType & 0xFF）
        frame[13] = 0;
        frame[14] = (byte) (cmdType & 0xFF);
        // [15+] payload
        System.arraycopy(payload, 0, frame, headerLen, payload.length);
        return frame;
    }

    @SuppressLint("MissingPermission")
    private void sendNextPacket() {
        if (!sending || sendIndex >= pendingPackets.size()) {
            return;
        }
        byte[] frame = pendingPackets.get(sendIndex);
        // 按 MTU 拆包顺序写（原版 sendPacketSequentially，包大小 = mtu - 3）
        chunkedFrame = frame;
        chunkIndex = 0;
        int chunkLen = Math.max(currentMtu - 3, 20);
        chunkCount = (frame.length + chunkLen - 1) / chunkLen;
        writeNextChunk();
    }

    private void writeNextChunk() {
        if (!sending || chunkedFrame == null || writeChar == null || gatt == null) {
            return;
        }
        int chunkLen = Math.max(currentMtu - 3, 20);
        int off = chunkIndex * chunkLen;
        if (off >= chunkedFrame.length) {
            onPacketWritten();
            return;
        }
        int len = Math.min(chunkLen, chunkedFrame.length - off);
        byte[] chunk = new byte[len];
        System.arraycopy(chunkedFrame, off, chunk, 0, len);
        writeChar.setValue(chunk);
        // 原版图片帧 fastWrite=true → WriteNoResponse（写失败时状态机靠 ACK 超时兜底）
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        gatt.writeCharacteristic(writeChar);
    }

    private void onPacketWritten() {
        if (!sending) return;
        chunkIndex++;
        if (chunkIndex < chunkCount) {
            writeNextChunk();
            return;
        }
        // 当前帧写完
        chunkedFrame = null;
        sendIndex++;
        if (diyHighlightMode) {
            // 高亮坐标帧：写完直接完成（原版 fastWrite 写完即 onSuccess，不等 ACK）
            if (sendIndex >= pendingPackets.size()) {
                sending = false;
                diyHighlightMode = false;
                Runnable done = diyHighlightDone;
                diyHighlightDone = null;
                if (done != null) done.run();
                Log.i(TAG, "diy highlight frame fully written");
            }
            return;
        }
        if (sendListener != null) {
            sendListener.onProgress(Math.min(sendIndex, totalPackets), totalPackets);
        }
        if (sendIndex >= pendingPackets.size()) {
            // 全部帧写完，等待完成 ACK（由 handleNotify 判定）
            Log.i(TAG, "all frames written, waiting ACK");
        } else {
            Log.i(TAG, "frame " + sendIndex + " written, waiting next ACK");
        }
    }

    /** 处理设备通知（ACK + 设备信息握手） */
    private void handleNotify(byte[] value) {
        Log.i(TAG, "notify: " + bytesToHex(value));
        // 设备信息握手响应：data[2..3] LE == 0x8001 硬件信息 / 0x8005 MCU 版本
        if (value.length >= 8) {
            int type = ((value[3] & 0xFF) << 8) | (value[2] & 0xFF);
            if (type == 0x8001) {
                int ledType = value[4] & 0xFF;
                Log.i(TAG, "hardware info: ledType=" + ledType);
                if (deviceInfoListener != null) {
                    deviceInfoListener.onHardwareInfo(ledType);
                }
                return;
            }
            if (type == 0x8005) {
                int major = value[4] & 0xFF;
                int minor = value[5] & 0xFF;
                String ver = major + "." + (minor == 0 ? "0" : String.format("%02d", minor));
                Log.i(TAG, "mcu version: " + ver);
                if (deviceInfoListener != null) {
                    deviceInfoListener.onMcuVersion(ver);
                }
                return;
            }
        }
        if (sendListener == null) return;
        // ACK 完整匹配（原版 awaitAck + Arrays.equals）：
        // 图片: {5,0,2,0,1}=下一帧 {5,0,2,0,3}=完成
        // 色号: {5,0,5,0,1}=下一帧 {5,0,5,0,3}=完成
        // 摄像头: {5,0,0,0,1}=完成 {5,0,0,0,3}=下一帧
        if (value.length == 5 && value[0] == 5 && value[1] == 0) {
            int ackType = value[2];
            int ack = value[4];
            boolean isNext = (ackType == 2 || ackType == 5) ? (ack == 1) : (ackType == 0 && ack == 3);
            boolean isDone = (ackType == 2 || ackType == 5) ? (ack == 3) : (ackType == 0 && ack == 1);
            if (isDone) {
                sending = false;
                SendListener l = sendListener;
                sendListener = null;
                if (l != null) l.onComplete();
            } else if (isNext) {
                // 请求下一帧（如果还没发完）
                if (sendIndex < pendingPackets.size()) {
                    sendNextPacket();
                }
            }
        }
    }

    // ==================== 命令帧（setDiyFunMode 等） ====================

    /**
     * 发送单条命令帧（如 setDiyFunMode: {5,0,4,1,mode}）
     * @param b0 帧第 0 字节
     * @param b1 帧第 1 字节
     * @param b2 帧第 2 字节（命令类型）
     * @param b3 帧第 3 字节
     * @param b4 帧第 4 字节（参数）
     */
    @SuppressLint("MissingPermission")
    public void sendCommand(int b0, int b1, int b2, int b3, int b4, SendListener listener) {
        if (!isConnected()) {
            listener.onFail("设备未连接");
            return;
        }
        byte[] frame = new byte[]{(byte) b0, (byte) b1, (byte) b2, (byte) b3, (byte) b4};
        sendRawCommand(frame, () -> {
            if (listener != null) {
                listener.onComplete();
            }
        });
    }

    /**
     * 发送任意命令帧（设备信息请求 {4,0,5,0x80} 等）
     * 严格串行：所有命令进入队列，写完一条再发下一条（对齐原版 BleGattQueue）
     * @param frame  命令字节数组
     * @param onDone 写入完成回调（成功或失败都会调用）
     */
    @SuppressLint("MissingPermission")
    public void sendRawCommand(byte[] frame, Runnable onDone) {
        if (!isConnected()) {
            Log.w(TAG, "sendRawCommand: not connected");
            if (onDone != null) onDone.run();
            return;
        }
        commandQueue.add(() -> {
            if (writeChar != null && gatt != null) {
                writeChar.setValue(frame);
                // 命令写类型自动选择（原版 sendPacketSequentially）：
                // 特征支持 Write with response → 用它；否则用 WriteNoResponse
                int props = writeChar.getProperties();
                boolean supportsWrite = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                if (supportsWrite) {
                    writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                } else {
                    writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                }
                gatt.writeCharacteristic(writeChar);
                Log.i(TAG, "raw command sent: " + bytesToHex(frame)
                        + " writeType=" + (supportsWrite ? "withResponse" : "noResponse"));
                // onDone 在写完成回调后执行（onCharacteristicWrite → drainCommandQueue 后）
                commandDone = onDone;
            } else {
                Log.w(TAG, "sendRawCommand: writeChar unavailable");
                if (onDone != null) onDone.run();
            }
        });
        drainCommandQueue();
    }

    /** 尝试发送下一条排队命令 */
    private void drainCommandQueue() {
        if (commandSending || commandQueue.isEmpty()) return;
        if (writeChar == null || gatt == null) return;
        commandSending = true;
        Runnable r = commandQueue.poll();
        if (r != null) r.run();
    }

    /** 请求设备信息（硬件类型/固件版本）——串行：写完成后自动接 getDeviceInfo 的调用方 */
    public void requestDeviceInfo(Runnable onDone) {
        sendRawCommand(new byte[]{4, 0, 5, (byte) 0x80}, onDone);
    }

    /**
     * 发送 getLedType 命令（原版流程：连接后先发此命令带当前时间，再发 getDeviceInfo）
     * 帧：{8, 0, 1, 0x80, HH, MM, SS, language}
     */
    public void sendLedTypeCommand(Runnable onDone) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        byte[] frame = new byte[]{
                8, 0, 1, (byte) 0x80,
                (byte) cal.get(java.util.Calendar.HOUR_OF_DAY),
                (byte) cal.get(java.util.Calendar.MINUTE),
                (byte) cal.get(java.util.Calendar.SECOND),
                1 // language
        };
        sendRawCommand(frame, onDone);
    }

    /**
     * 发送 DIY 单色高亮帧（原版 sendDiyImageData → payload(type=5)）
     * 帧格式：{lenLE(2), 9,1, option, data}
     * data = {r,g,b, x1,y1, x2,y2, ...}（颜色 + 坐标点列表，坐标 0-based）
     * 关键：按 MTU 拆包发送（原版 send → toPacketList(mtu-3) + sendPacketSequentially），
     * 写完所有包才回调 onDone（fastWrite=WriteNoResponse，不等 ACK）。
     * @param rgb       单色 RGB（r,g,b 三字节）
     * @param points    坐标点数组（每点 2 字节 x,y）
     * @param option    0=首包 2=续包
     * @param onDone    全部写完回调
     */
    @SuppressLint("MissingPermission")
    public void sendDiyHighlight(byte[] rgb, byte[] points, int option, Runnable onDone) {
        if (!isConnected()) {
            Log.w(TAG, "sendDiyHighlight: not connected");
            if (onDone != null) onDone.run();
            return;
        }
        byte[] data = new byte[3 + points.length];
        data[0] = rgb[0];
        data[1] = rgb[1];
        data[2] = rgb[2];
        System.arraycopy(points, 0, data, 3, points.length);
        int frameLen = data.length + 5;
        byte[] frame = new byte[frameLen];
        frame[0] = (byte) (frameLen & 0xFF);
        frame[1] = (byte) ((frameLen >> 8) & 0xFF);
        frame[2] = 9; // 高亮类型（getDataType(5, true)：ledType 2/3 → {9,1}）
        frame[3] = 1;
        frame[4] = (byte) option;
        System.arraycopy(data, 0, frame, 5, data.length);
        Log.i(TAG, "diy highlight frame: " + bytesToHex(frame).substring(0, Math.min(60, bytesToHex(frame).length()))
                + " ... points=" + (points.length / 2));

        // 拆包发送（不走命令队列——命令队列用于短命令；这里帧可能很大）
        // 注意：不能与 sendFrames 的 sending 状态冲突
        if (sending) {
            Log.w(TAG, "sendDiyHighlight: send in progress, queueing");
            handler.postDelayed(() -> sendDiyHighlight(rgb, points, option, onDone), 200);
            return;
        }
        pendingPackets.clear();
        pendingPackets.add(frame);
        sendIndex = 0;
        sending = true;
        diyHighlightMode = true;
        diyHighlightDone = onDone;
        sendNextPacket();
    }

    private boolean diyHighlightMode;
    private Runnable diyHighlightDone;

    /**
     * 发送完整图纸（重新显示完整图案，取消高亮时用）——对齐原版 sendEditEnterPreviewBitmap 路径
     */
    public void sendFullImage(byte[] rgbData, int ledType, SendListener listener) {
        sendFrames(rgbData, 1, ledType, false, listener);
    }

    // ==================== 工具 ====================

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }
}
