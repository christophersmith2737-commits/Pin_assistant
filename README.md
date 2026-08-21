# PinGB 拼豆助手 (Pin Bead Assistant)

> 识别拼豆图纸 + 通过 BLE 发送到拼豆板的 Android 应用。
> 与 [PinGB 拼豆图纸生成器](https://github.com/christophersmith2737-commits/PinGB) 网页版配套使用。

## 功能

- **PinGB 网页识别**：内嵌 PinGB WebView，上传图片 → AI 像素化 → 多色号系统（MARD/COCO/漫漫/盼盼/咪小窝）→ 噪点识别补色 → 手动编辑
- **导入图纸文件**：导入 PinGB 网页版「下载图纸文件」导出的 JSON（`{N, M, colorSystem, pixels}`），电脑上识别好的图纸一键发到手机
- **BLE 连接拼豆板**：扫描并连接拼豆板（104×104 LED），支持设备信息读取（LED 类型 / MCU 固件版本）
- **色号筛选发送**：按 MARD 色号逐色发送，深色色号（亮度 < 90）单独分组并以绿色高亮显示，方便按色号分批拼豆
- **高亮坐标帧发送**：发送单色点位时使用坐标帧协议（每帧 ≤2048 点自动分块，叠加模式不刷新底色）
- **文字滚动动画**：在拼豆板上播放滚动的文字动画（可调倍速、循环播放）

## 快速开始

### 直接安装

下载 [`releases/PinGB-App.apk`](releases/PinGB-App.apk)（debug 签名，Android 8.0+），侧载安装到手机。

### 从源码构建

依赖：

- JDK 17+（javac）
- Android SDK：`build-tools;36.0.0`、`platforms;android-37`（`android.jar`）
- Python 3（用于 APK 组装脚本）

```bat
build.bat
```

产物：`PinGB-App.apk`（已签名，可直接安装）。

## 使用流程

1. **电脑端**：打开 [PinGB 网页版](https://github.com/christophersmith2737-commits/PinGB)，识别/编辑图纸 → 点击「下载图纸文件」得到 `pindoudou_NxM.json`
2. **传输**：把 json 发到手机（QQ/微信/数据线均可），App 内「导入图纸文件」会直接打开下载目录
3. **发送**：打开拼豆板电源 → App 内「连接拼豆板 (BLE)」→ 自动连接并读取设备信息 → 「发送图纸」按色号逐批发送 → 按绿色高亮色号分组拼豆

## 技术细节

- **纯 Java 手写 UI**（无 Gradle / 无第三方依赖），直接使用 Android 框架 `android.bluetooth` + `org.json`
- **BLE 协议**：帧结构、ACK 时序、高亮坐标帧分块规则等完整通信笔记见 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)
- **打包方式**：`javac → d8 → aapt2(compile/link) → assemble.py → zipalign → apksigner`，全程命令行，无 Gradle

## 目录结构

```
src/com/pingb/app/
├── MainActivity.java        # 主界面：选图 / 导入图纸 / 入口
├── RecognizeActivity.java   # PinGB WebView 识别页（内嵌 assets/pingb）
├── SendActivity.java        # 发送页：BLE 连接、色号筛选、逐色发送
├── TextAnimActivity.java    # 文字滚动动画
├── DataConverter.java       # 图纸 JSON 解析（含 BOM 容错）
├── MardColorMapping.java    # 291 色 hex → MARD 色号映射
└── ble/
    ├── BleManager.java      # GATT 连接、命令队列、分帧发送
    └── Crc32.java           # CRC32（协议校验）
```

## 许可证

[Apache License 2.0](LICENSE)
