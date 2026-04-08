# LibSerial

Android 串口通信工具库，基于 NDK 实现串口读写，封装为 `LSerialUtil` 工具类，支持 **HEX** 和 **ASCII** 两种通信模式，提供打开、发送、接收、重连、断开、大数据分包发送等功能。

## 功能特性

- 支持 HEX（十六进制）和 ASCII 两种通信模式
- 支持标准串口 (`/dev/ttyS`) 和 WK 扩展串口 (`/dev/ttysWK`)
- 支持自定义串口路径（如 `/dev/ttyUSB10`）
- 可配置波特率、数据位、停止位、校验位
- 接收数据自动聚合，主线程回调
- 支持大数据分包发送（带进度回调和取消功能）
- 提供连接状态查询、重连机制
- 内置 HEX 与 byte[] 互转工具方法

## 引入方式

在项目 `settings.gradle` 中添加：

```groovy
include ':serial'
```

在 app 模块的 `build.gradle` 中添加依赖：

```groovy
dependencies {
    implementation project(path: ':serial')
}
```

## 构造参数说明

| 参数 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| `port` | int | 串口号 (0~6) | - |
| `portPath` | String | 串口完整路径（用于非标准路径） | - |
| `baudRate` | int | 波特率 | - |
| `dataBits` | int | 数据位 | 8 |
| `stopBits` | int | 停止位 | 1 |
| `parity` | char | 校验位 (`'N'`, `'E'`, `'O'`) | `'N'` |
| `serialType` | SerialType | 通信模式（`TYPE_HEX` / `TYPE_ASCII`） | - |
| `serialNameType` | SerialNameType | 串口设备名类型（`TYPE_TTYS` / `TYPE_TTYS_WK`） | `TYPE_TTYS` |
| `listener` | OnSerialListener | 回调监听器 | - |

> 构造时会自动调用 `open()` 打开串口。

## API 方法

### 连接管理

| 方法 | 说明 |
|------|------|
| `open()` | 打开串口并启动接收线程，返回 boolean |
| `disconnect()` | 断开串口连接并释放所有资源 |
| `reconnect()` | 重连串口（先断开再打开），返回 boolean |
| `isConnected()` | 查询串口是否已连接 |

### 发送数据

| 方法 | 说明 |
|------|------|
| `sendHex(byte[])` | 发送 HEX 字节数据 |
| `sendHex(String)` | 发送 HEX 字符串（如 `"FF01A3"`） |
| `sendAscii(String)` | 发送 ASCII 字符串（不带换行） |
| `sendAsciiLine(String)` | 发送 ASCII 字符串（带 `\n\r` 换行） |
| `sendHexChunked(byte[], listener)` | 分包发送大数据（默认 1024 字节/包，5ms 间隔） |
| `sendHexChunked(byte[], chunkSize, delayMs, listener)` | 分包发送大数据（自定义包大小和间隔） |
| `cancelChunkedSend()` | 取消正在进行的分包发送 |
| `isChunkedSending()` | 查询是否正在分包发送 |

### 状态查询

| 方法 | 说明 |
|------|------|
| `getPortPath()` | 获取当前串口路径 |
| `getBaudRate()` | 获取当前波特率 |
| `getSerialType()` | 获取通信模式 |

### 静态工具方法

| 方法 | 说明 |
|------|------|
| `bytesToHexString(byte[], int)` | 字节数组转十六进制字符串 |
| `bytesToHexString(byte[])` | 字节数组转十六进制字符串（全部） |
| `hexStringToBytes(String)` | 十六进制字符串转字节数组 |
| `hexStringToDisplayFormat(String)` | 十六进制字符串转带空格显示格式（`"FF01A3"` → `"FF 01 A3"`） |

## 回调接口 OnSerialListener

| 方法 | 说明 |
|------|------|
| `onOpenError(String portPath, Exception e)` | 串口打开失败 |
| `onReceiveError(Exception e)` | 数据接收异常 |
| `onSendError(Exception e)` | 数据发送异常 |
| `onDataReceived(String data)` | 接收到数据（主线程回调）。HEX 模式为十六进制字符串，ASCII 模式为文本 |

> 所有回调方法均有默认空实现，可按需覆盖。

## 分包发送进度回调 OnSendProgressListener

| 方法 | 说明 |
|------|------|
| `onProgress(int sentBytes, int totalBytes)` | 发送进度回调（发送线程） |
| `onComplete(int totalBytes)` | 发送完成 |
| `onError(int sentBytes, Exception e)` | 发送失败 |
| `onCancelled(int sentBytes)` | 发送被取消 |

## 使用示例

### 1. HEX 模式（最简用法）

```java
LSerialUtil serial = new LSerialUtil(1, 9600, LSerialUtil.SerialType.TYPE_HEX,
        new LSerialUtil.OnSerialListener() {
            @Override
            public void onDataReceived(String data) {
                // data 为十六进制字符串，如 "FF01A3"
                Log.d("Serial", "收到HEX: " + data);
            }

            @Override
            public void onOpenError(String portPath, Exception e) {
                Log.e("Serial", "串口打开失败: " + portPath);
            }
        });

// 发送字节数据
serial.sendHex(new byte[]{0x01, 0x02, 0x03});

// 发送十六进制字符串
serial.sendHex("FF01A3");

// 断开
serial.disconnect();
```

### 2. ASCII 模式

```java
LSerialUtil serial = new LSerialUtil(1, 115200, LSerialUtil.SerialType.TYPE_ASCII,
        new LSerialUtil.OnSerialListener() {
            @Override
            public void onDataReceived(String data) {
                Log.d("Serial", "收到ASCII: " + data);
            }
        });

// 发送（不带换行）
serial.sendAscii("AT");

// 发送（带换行 \n\r）
serial.sendAsciiLine("AT+RST");

serial.disconnect();
```

### 3. 指定 WK 扩展串口

```java
LSerialUtil serial = new LSerialUtil(
        LSerialUtil.SerialNameType.TYPE_TTYS_WK, 3, 9600,
        LSerialUtil.SerialType.TYPE_HEX, listener);
// 对应路径: /dev/ttysWK3
```

### 4. 自定义串口路径

```java
LSerialUtil serial = new LSerialUtil(
        "/dev/ttyUSB0", 9600,
        LSerialUtil.SerialType.TYPE_HEX, listener);
```

### 5. 自定义数据位/停止位/校验位

```java
LSerialUtil serial = new LSerialUtil(
        1, 9600, 8, 1, 'E',
        LSerialUtil.SerialType.TYPE_HEX, listener);
// 数据位8, 停止位1, 偶校验
```

### 6. 大数据分包发送

```java
byte[] imageData = ...; // 大数据

serial.sendHexChunked(imageData, new LSerialUtil.OnSendProgressListener() {
    @Override
    public void onProgress(int sentBytes, int totalBytes) {
        int percent = sentBytes * 100 / totalBytes;
        Log.d("Serial", "发送进度: " + percent + "%");
    }

    @Override
    public void onComplete(int totalBytes) {
        Log.d("Serial", "发送完成: " + totalBytes + " 字节");
    }

    @Override
    public void onError(int sentBytes, Exception e) {
        Log.e("Serial", "发送失败: " + e.getMessage());
    }

    @Override
    public void onCancelled(int sentBytes) {
        Log.w("Serial", "发送取消, 已发送: " + sentBytes);
    }
});

// 自定义分包大小和间隔
serial.sendHexChunked(imageData, 512, 10, progressListener);

// 取消发送
serial.cancelChunkedSend();
```

### 7. 重连

```java
if (!serial.isConnected()) {
    boolean success = serial.reconnect();
}
```

### 8. HEX 工具方法

```java
// byte[] → HEX字符串
String hex = LSerialUtil.bytesToHexString(new byte[]{(byte)0xFF, 0x01}, 2);
// 结果: "FF01"

// HEX字符串 → byte[]
byte[] bytes = LSerialUtil.hexStringToBytes("FF01A3");

// HEX字符串 → 带空格显示
String display = LSerialUtil.hexStringToDisplayFormat("FF01A3");
// 结果: "FF 01 A3"
```

