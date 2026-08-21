# PixDotDot（拼豆豆）BLE 协议逆向笔记

> 逆向自 PixDotDot 原版 App（`BaseSend.kt` / `BleManager`），仅用于学习研究。

## 1. 服务与特征

- 真实设备（拼豆板）广播的服务列表含 `fa00` 服务：
  - `fa02`：写入特征（Write）
  - `fa03`：通知特征（Notify）
- **写特征选择逻辑**（与原版一致）：遍历设备全部服务，找「同时具备 WriteNoResponse + Notify 的同服务特征对」，不能写死 UUID。
- 特征只支持 WriteNoResponse 时，必须用 `WRITE_TYPE_NO_RESPONSE` 写，否则 GATT status=3。

## 2. 连接初始化（严格串行）

```
CCCD descriptor 写入完成 → MTU 512 协商完成 → 才回调 onConnected
```

并发写会丢回调，因此所有命令走串行队列（commandQueue）。

## 3. 连接后命令链

```
setSwitchScreen(1)                    // 切换屏幕模式
  → getLedType(带时间 {8,0,1,0x80,HH,MM,SS,1})
  → getDeviceInfo({4,0,5,0x80})
```

设备信息响应 `data[2..3]` 小端：
- `0x8001` → LED 类型（ledType），`3` = 104×104 板
- `0x8005` → MCU 固件版本（如 v3.09）

## 4. 数据帧格式（payload 15 字节头 + 数据）

```
[0-1]   lenLE       帧总长 = payload + 15（含 15 字节头）
[2-3]   dataType    LE：图片=0x0002 | 色号=0x0005 | 高亮坐标=0x0009
[4]     option      0 = 首块 / 2 = 续块
[5-8]   totalLenLE  全数据总长（LE 4 字节）
[9-12]  CRC32LE     对全数据（LE）
[13]    = 0
[14]    cmdType     1
[15+]   data
```

### 命令帧（色号/命令类，dataType=5，5 字节头）

```
[0-1] dataType=0x0005 | [2] option | [3] = 0 | [4] cmdType(1)
```

### ACK（通知帧）

- 图片发送：`{5,0,2,0,1}` = 请求下一块（next），`{5,0,2,0,3}` = 完成（done）
- 色号发送：`{5,0,5,0,1}` / `{5,0,5,0,3}`（1 = 继续，3 = 完成）
- 摄像头模式：`{5,0,0,0,3/1}`

## 5. 高亮坐标帧（dataType=9，sendDiyImageData）

- 每帧**最多 2048 个点**，超出必须分块（splitByPointLimit）：首块 option=0，后续 option=2
- 发送前必须先 `setDiyFunMode(3)`（`ENTER_NO_CLEAR`）进入叠加模式，否则会清屏
- 纯黑底图会被忽略 → 用深灰底图 RGB(40,40,40)
- `{0,0}` 图片帧是 fallback，会超时恢复，正常不应走到

## 6. DiyImageFun 模式枚举

| 值 | 含义 |
| --- | --- |
| 0 | QUIT_NOSAVE_KEEP_PREV（退出不保存） |
| 1 | ENTER_CLEAR_CUR_SHOW（进入并清屏显示——普通显示模式必须用 1） |
| 2 | QUIT_STILL_CUR_SHOW（退出保留当前显示） |
| 3 | ENTER_NO_CLEAR_CUR_SHOW（进入不清屏——只能在已 DIY 模式下用） |

## 7. 其它要点

- 高亮模式写完分块**不等 ACK**，直接 done，避免超时
- 图片发送 data 为 RGB 字节流（每格 3 字节，行主序），空位 RGB(0,0,0)
- 色号发送 data 为 MARD 色号字符串
- 发送前建议 setSwitchScreen(1) 确保屏幕处于可绘制状态
