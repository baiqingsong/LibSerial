package com.dawn.serial;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dawn.util_fun.LLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

import android_serialport_api.SafeSerialPort;

/**
 * 串口工具类
 * <p>
 * 支持 HEX 和 ASCII 两种通信模式，提供打开、发送、接收、重连、断开等功能。
 * <p>
 * 使用示例：
 * <pre>
 *   LSerialUtil serial = new LSerialUtil(1, 9600, SerialType.HEX, listener);
 *   serial.sendHex(new byte[]{0x01, 0x02});
 *   serial.disconnect();
 * </pre>
 */
@SuppressWarnings("unused")
public class LSerialUtil {
    private static final String TAG = "LSerialUtil";

    // ==================== 枚举类型 ====================

    /** 串口通信模式 */
    public enum SerialType {
        /** HEX（十六进制）模式 */
        TYPE_HEX,
        /** ASCII 文本模式 */
        TYPE_ASCII
    }

    /** 串口设备名类型 */
    public enum SerialNameType {
        /** 标准串口: /dev/ttyS{n} */
        TYPE_TTYS,
        /** WK 扩展串口: /dev/ttysWK{n} */
        TYPE_TTYS_WK
    }

    // ==================== 串口路径前缀 ====================
    private static final String PATH_PREFIX_TTYS = "/dev/ttyS";
    private static final String PATH_PREFIX_TTYS_WK = "/dev/ttysWK";
    private static final int DEFAULT_PORT = 1;

    // ==================== 默认配置常量 ====================
    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_STOP_BITS = 1;
    private static final char DEFAULT_PARITY = 'N';
    private static final int HEX_READ_BUFFER_SIZE = 256;
    private static final int DEFAULT_CHUNK_SIZE = 1024;
    private static final int DEFAULT_CHUNK_DELAY_MS = 5;

    // ==================== Handler消息常量 ====================
    private static final int MSG_HEX_DATA_READY = 1;
    private static final int MSG_ASCII_DATA_READY = 2;
    private static final int DELAY_HEX_AGGREGATE_MS = 20;
    private static final int DELAY_ASCII_AGGREGATE_MS = 10;

    // ==================== 串口配置字段（不可变） ====================
    private final String mPortPath;
    private final int mBaudRate;
    private final int mDataBits;
    private final int mStopBits;
    private final char mParity;
    private final SerialType mSerialType;
    private final OnSerialListener mListener;

    // ==================== IO流字段 ====================
    private PrintWriter mPrintWriter;
    private BufferedReader mBufferedReader;
    private OutputStream mOutputStream;
    private InputStream mInputStream;

    // ==================== 状态与缓存字段 ====================
    private volatile boolean mIsConnected;
    private final StringBuilder mHexReceiveCache = new StringBuilder();
    private final StringBuilder mAsciiReceiveCache = new StringBuilder();
    private SafeSerialPort mSerialPort;
    private Thread mReceiverThread;
    private final SerialHandler mHandler = new SerialHandler(this);
    private final Object mSendLock = new Object();
    private volatile boolean mCancelChunkedSend;
    private Thread mChunkedSendThread;

    // ==================== 构造方法 ====================

    /**
     * 简易构造：默认 ttyS 类型，默认数据位/停止位/校验位
     *
     * @param port       串口号 (0~6)
     * @param baudRate   波特率
     * @param serialType 通信模式
     * @param listener   回调监听器
     */
    public LSerialUtil(int port, int baudRate, SerialType serialType, OnSerialListener listener) {
        this(SerialNameType.TYPE_TTYS, port, baudRate, DEFAULT_DATA_BITS, DEFAULT_STOP_BITS, DEFAULT_PARITY, serialType, listener);
    }

    /**
     * 指定串口名类型的简易构造
     *
     * @param serialNameType 串口设备名类型
     * @param port           串口号 (0~6)
     * @param baudRate       波特率
     * @param serialType     通信模式
     * @param listener       回调监听器
     */
    public LSerialUtil(SerialNameType serialNameType, int port, int baudRate, SerialType serialType, OnSerialListener listener) {
        this(serialNameType, port, baudRate, DEFAULT_DATA_BITS, DEFAULT_STOP_BITS, DEFAULT_PARITY, serialType, listener);
    }

    /**
     * 自定义数据位/停止位/校验位构造（默认 ttyS 类型）
     *
     * @param port       串口号 (0~6)
     * @param baudRate   波特率
     * @param dataBits   数据位
     * @param stopBits   停止位
     * @param parity     校验位 ('N', 'E', 'O')
     * @param serialType 通信模式
     * @param listener   回调监听器
     */
    public LSerialUtil(int port, int baudRate, int dataBits, int stopBits, char parity, SerialType serialType, OnSerialListener listener) {
        this(SerialNameType.TYPE_TTYS, port, baudRate, dataBits, stopBits, parity, serialType, listener);
    }

    /**
     * 自定义串口路径构造（用于非标准串口路径如 /dev/ttyUSB10）
     *
     * @param portPath   串口完整路径
     * @param baudRate   波特率
     * @param serialType 通信模式
     * @param listener   回调监听器
     */
    public LSerialUtil(String portPath, int baudRate, SerialType serialType, OnSerialListener listener) {
        this(portPath, baudRate, DEFAULT_DATA_BITS, DEFAULT_STOP_BITS, DEFAULT_PARITY, serialType, listener);
    }

    /**
     * 自定义串口路径的完整参数构造
     *
     * @param portPath   串口完整路径
     * @param baudRate   波特率
     * @param dataBits   数据位
     * @param stopBits   停止位
     * @param parity     校验位 ('N', 'E', 'O')
     * @param serialType 通信模式
     * @param listener   回调监听器
     */
    public LSerialUtil(String portPath, int baudRate, int dataBits, int stopBits, char parity,
                       SerialType serialType, OnSerialListener listener) {
        this.mPortPath = portPath;
        this.mBaudRate = baudRate;
        this.mDataBits = dataBits;
        this.mStopBits = stopBits;
        this.mParity = parity;
        this.mSerialType = serialType;
        this.mListener = listener;
        LLog.d(TAG, "init portPath=" + portPath + ", baudRate=" + baudRate
                + ", dataBits=" + dataBits + ", stopBits=" + stopBits + ", parity=" + parity);
        open();
    }

    /**
     * 主构造方法（所有其他构造方法最终委托到此）
     *
     * @param serialNameType 串口设备名类型
     * @param port           串口号 (0~6)
     * @param baudRate       波特率
     * @param dataBits       数据位
     * @param stopBits       停止位
     * @param parity         校验位 ('N', 'E', 'O')
     * @param serialType     通信模式
     * @param listener       回调监听器
     */
    public LSerialUtil(SerialNameType serialNameType, int port, int baudRate, int dataBits,
                       int stopBits, char parity, SerialType serialType, OnSerialListener listener) {
        this.mPortPath = buildPortPath(serialNameType, port);
        this.mBaudRate = baudRate;
        this.mDataBits = dataBits;
        this.mStopBits = stopBits;
        this.mParity = parity;
        this.mSerialType = serialType;
        this.mListener = listener;
        LLog.d(TAG, "init portPath=" + mPortPath + ", baudRate=" + baudRate
                + ", dataBits=" + dataBits + ", stopBits=" + stopBits + ", parity=" + parity);
        open();
    }

    // ==================== 串口路径工具 ====================

    /**
     * 根据串口类型和端口号生成串口设备路径
     *
     * @param nameType 串口设备名类型
     * @param port     串口号（负数或超大值使用默认端口1）
     * @return 串口设备路径，如 "/dev/ttyS1" 或 "/dev/ttysWK3"
     */
    private static String buildPortPath(SerialNameType nameType, int port) {
        if (port < 0) {
            port = DEFAULT_PORT;
        }
        String prefix = (nameType == SerialNameType.TYPE_TTYS_WK) ? PATH_PREFIX_TTYS_WK : PATH_PREFIX_TTYS;
        return prefix + port;
    }

    // ==================== 打开/关闭/重连 ====================

    /**
     * 打开串口并启动接收线程
     *
     * @return true 打开成功，false 打开失败
     */
    public boolean open() {
        if (mIsConnected) {
            LLog.w(TAG, "串口已处于打开状态: " + mPortPath);
            return true;
        }
        try {
            mSerialPort = new SafeSerialPort(
                    new File(mPortPath),
                    mBaudRate,
                    mDataBits,
                    mStopBits,
                    mParity
            );

            // 先标记连接状态，再启动接收线程（避免线程检查 mIsConnected 时为 false 而立即退出）
            mIsConnected = true;

            switch (mSerialType) {
                case TYPE_HEX:
                    mOutputStream = mSerialPort.getOutputStream();
                    mInputStream = mSerialPort.getInputStream();
                    startHexReceiver();
                    break;
                case TYPE_ASCII:
                    mPrintWriter = new PrintWriter(new BufferedWriter(
                            new OutputStreamWriter(mSerialPort.getOutputStream(), StandardCharsets.UTF_8)), true);
                    mBufferedReader = new BufferedReader(
                            new InputStreamReader(mSerialPort.getInputStream(), StandardCharsets.UTF_8));
                    startAsciiReceiver();
                    break;
            }
            LLog.i(TAG, "串口打开成功: " + mPortPath + ", 波特率: " + mBaudRate);
            return true;
        } catch (Exception e) {
            LLog.e(TAG, "串口打开失败: " + mPortPath + ", " + e.getMessage());
            mIsConnected = false;
            // 清理已创建但未完全初始化的资源
            closeStreams();
            closeSerialPort();
            if (mListener != null) {
                mListener.onOpenError(mPortPath, e);
            }
            return false;
        }
    }

    /**
     * 断开串口连接并释放所有资源
     */
    public void disconnect() {
        mIsConnected = false;
        try {
            cancelChunkedSend();
            stopReceiverThread();
            closeStreams();
            closeSerialPort();
            mHandler.removeCallbacksAndMessages(null);
            clearReceiveCache();
            LLog.i(TAG, "串口断开成功: " + mPortPath);
        } catch (Exception e) {
            LLog.e(TAG, "断开串口异常: " + e.getMessage());
        }
    }

    /**
     * 断开串口连接（兼容旧方法名）
     *
     * @deprecated 使用 {@link #disconnect()} 代替
     */
    @Deprecated
    public void disConnect() {
        disconnect();
    }

    /**
     * 重连串口（先断开再打开）
     *
     * @return true 重连成功，false 重连失败
     */
    public boolean reconnect() {
        LLog.i(TAG, "串口重连: " + mPortPath);
        disconnect();
        return open();
    }

    // ==================== 发送方法 ====================

    /**
     * 发送 HEX 字节数据
     *
     * @param bytes 要发送的字节数组
     */
    public void sendHex(@NonNull byte[] bytes) {
        if (!mIsConnected) {
            LLog.w(TAG, "串口未连接，无法发送");
            return;
        }
        synchronized (mSendLock) {
            try {
                if (mOutputStream != null) {
                    mOutputStream.write(bytes);
                    mOutputStream.flush();
                }
            } catch (Exception e) {
                LLog.e(TAG, "发送HEX数据失败: " + e.getMessage());
                if (mListener != null) {
                    mListener.onSendError(e);
                }
            }
        }
    }

    /**
     * 发送 HEX 字符串数据（如 "FF01A3"）
     *
     * @param hexStr 十六进制字符串（不含空格）
     */
    public void sendHex(@NonNull String hexStr) {
        try {
            sendHex(hexStringToBytes(hexStr));
        } catch (IllegalArgumentException e) {
            LLog.e(TAG, "HEX字符串格式错误: " + e.getMessage());
            if (mListener != null) {
                mListener.onSendError(e);
            }
        }
    }

    /**
     * 发送 HEX 字节数据（兼容旧方法名）
     *
     * @deprecated 使用 {@link #sendHex(byte[])} 代替
     */
    @Deprecated
    public void sendHexMsg(@NonNull byte[] bytes) {
        sendHex(bytes);
    }

    /**
     * 发送 HEX 字符串数据（兼容旧方法名）
     *
     * @deprecated 使用 {@link #sendHex(String)} 代替
     */
    @Deprecated
    public void sendHexMsg(@NonNull String hexStr) {
        sendHex(hexStr);
    }

    /**
     * 发送 ASCII 字符串（带换行 \n\r）
     *
     * @param msg 要发送的消息
     */
    public void sendAsciiLine(@NonNull String msg) {
        if (!mIsConnected) {
            LLog.w(TAG, "串口未连接，无法发送");
            return;
        }
        synchronized (mSendLock) {
            try {
                if (mPrintWriter != null) {
                    mPrintWriter.write(msg);
                    mPrintWriter.write("\n\r");
                    mPrintWriter.flush();
                }
            } catch (Exception e) {
                LLog.e(TAG, "发送ASCII数据失败: " + e.getMessage());
                if (mListener != null) {
                    mListener.onSendError(e);
                }
            }
        }
    }

    /**
     * 发送 ASCII 字符串（带换行，兼容旧方法名）
     *
     * @deprecated 使用 {@link #sendAsciiLine(String)} 代替
     */
    @Deprecated
    public void sendAsciiMsg(@NonNull String msg) {
        sendAsciiLine(msg);
    }

    /**
     * 发送 ASCII 字符串（不带换行）
     *
     * @param msg 要发送的消息
     */
    public void sendAscii(@NonNull String msg) {
        if (!mIsConnected) {
            LLog.w(TAG, "串口未连接，无法发送");
            return;
        }
        synchronized (mSendLock) {
            try {
                if (mPrintWriter != null) {
                    mPrintWriter.print(msg);
                    mPrintWriter.flush();
                }
            } catch (Exception e) {
                LLog.e(TAG, "发送ASCII数据失败: " + e.getMessage());
                if (mListener != null) {
                    mListener.onSendError(e);
                }
            }
        }
    }

    // ==================== 大数据分包发送 ====================

    /**
     * 大数据发送进度回调
     */
    public interface OnSendProgressListener {
        /**
         * 发送进度回调（在发送线程回调，非主线程）
         *
         * @param sentBytes  已发送字节数
         * @param totalBytes 总字节数
         */
        void onProgress(int sentBytes, int totalBytes);

        /**
         * 发送完成
         *
         * @param totalBytes 总字节数
         */
        default void onComplete(int totalBytes) { }

        /**
         * 发送失败
         *
         * @param sentBytes 已发送字节数
         * @param e         异常
         */
        default void onError(int sentBytes, @Nullable Exception e) { }

        /**
         * 发送被取消
         *
         * @param sentBytes 已发送字节数
         */
        default void onCancelled(int sentBytes) { }
    }

    /**
     * 分包发送大数据（异步，使用默认分包大小 1024 字节，默认间隔 5ms）
     * <p>
     * 适用于图片等大数据传输场景，避免一次性写入导致硬件缓冲区溢出。
     *
     * @param data     要发送的完整数据
     * @param listener 进度回调（可为 null）
     */
    public void sendHexChunked(@NonNull byte[] data, @Nullable OnSendProgressListener listener) {
        sendHexChunked(data, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_DELAY_MS, listener);
    }

    /**
     * 分包发送大数据（异步）
     * <p>
     * 数据会被拆分为多个小包依次发送，每包之间可设置间隔时间，
     * 避免串口硬件缓冲区溢出，支持进度回调和中途取消。
     * <p>
     * 使用 {@link #cancelChunkedSend()} 可取消正在进行的分包发送。
     *
     * @param data         要发送的完整数据
     * @param chunkSize    每包大小（字节），建议 256~2048
     * @param chunkDelayMs 每包发送后的等待时间（毫秒），0 表示无延迟
     * @param listener     进度回调（可为 null）
     */
    public void sendHexChunked(@NonNull byte[] data, int chunkSize, int chunkDelayMs,
                               @Nullable OnSendProgressListener listener) {
        if (!mIsConnected) {
            LLog.w(TAG, "串口未连接，无法发送");
            return;
        }
        if (data.length == 0) {
            if (listener != null) {
                listener.onComplete(0);
            }
            return;
        }
        if (chunkSize <= 0) {
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        if (chunkDelayMs < 0) {
            chunkDelayMs = 0;
        }

        // 如果数据较小，直接同步发送
        if (data.length <= chunkSize) {
            sendHex(data);
            if (listener != null) {
                listener.onProgress(data.length, data.length);
                listener.onComplete(data.length);
            }
            return;
        }

        // 取消上一次未完成的分包发送
        cancelChunkedSend();

        mCancelChunkedSend = false;
        final int finalChunkSize = chunkSize;
        final int finalChunkDelayMs = chunkDelayMs;
        final int totalBytes = data.length;

        mChunkedSendThread = new Thread(() -> {
            int offset = 0;
            try {
                while (offset < totalBytes && !mCancelChunkedSend && mIsConnected) {
                    int remaining = totalBytes - offset;
                    int sendSize = Math.min(finalChunkSize, remaining);

                    synchronized (mSendLock) {
                        if (mOutputStream != null) {
                            mOutputStream.write(data, offset, sendSize);
                            mOutputStream.flush();
                        } else {
                            throw new IOException("OutputStream is null");
                        }
                    }

                    offset += sendSize;

                    if (listener != null) {
                        listener.onProgress(offset, totalBytes);
                    }

                    // 非最后一包时，等待间隔
                    if (offset < totalBytes && finalChunkDelayMs > 0 && !mCancelChunkedSend) {
                        Thread.sleep(finalChunkDelayMs);
                    }
                }

                if (mCancelChunkedSend) {
                    LLog.i(TAG, "分包发送已取消, 已发送: " + offset + "/" + totalBytes);
                    if (listener != null) {
                        listener.onCancelled(offset);
                    }
                } else {
                    LLog.i(TAG, "分包发送完成: " + totalBytes + " 字节");
                    if (listener != null) {
                        listener.onComplete(totalBytes);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LLog.i(TAG, "分包发送线程被中断, 已发送: " + offset + "/" + totalBytes);
                if (listener != null) {
                    listener.onCancelled(offset);
                }
            } catch (Exception e) {
                LLog.e(TAG, "分包发送失败: " + e.getMessage() + ", 已发送: " + offset + "/" + totalBytes);
                if (listener != null) {
                    listener.onError(offset, e);
                }
                if (mListener != null) {
                    mListener.onSendError(e);
                }
            }
        }, "SerialChunkedSend-" + mPortPath);
        mChunkedSendThread.start();
    }

    /**
     * 取消正在进行的分包发送
     */
    public void cancelChunkedSend() {
        mCancelChunkedSend = true;
        if (mChunkedSendThread != null) {
            mChunkedSendThread.interrupt();
            try {
                mChunkedSendThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mChunkedSendThread = null;
        }
    }

    /**
     * 是否正在进行分包发送
     */
    public boolean isChunkedSending() {
        return mChunkedSendThread != null && mChunkedSendThread.isAlive() && !mCancelChunkedSend;
    }

    // ==================== 接收线程 ====================

    /**
     * 启动 HEX 模式接收线程
     */
    private void startHexReceiver() {
        mReceiverThread = new Thread(() -> {
            byte[] buffer = new byte[HEX_READ_BUFFER_SIZE];
            while (!Thread.currentThread().isInterrupted() && mIsConnected) {
                try {
                    int size = mInputStream.read(buffer);
                    if (size == -1) {
                        // 流已结束
                        LLog.w(TAG, "HEX输入流已结束: " + mPortPath);
                        break;
                    }
                    if (size > 0) {
                        appendHexCache(bytesToHexString(buffer, size));
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted() && mIsConnected) {
                        LLog.e(TAG, "HEX接收错误: " + e.getMessage());
                        if (mListener != null) {
                            mListener.onReceiveError(e);
                        }
                    }
                    break;
                }
            }
        }, "SerialHexReceiver-" + mPortPath);
        mReceiverThread.start();
    }

    /**
     * 启动 ASCII 模式接收线程
     */
    private void startAsciiReceiver() {
        mReceiverThread = new Thread(() -> {
            String line;
            while (!Thread.currentThread().isInterrupted() && mIsConnected) {
                try {
                    if ((line = mBufferedReader.readLine()) != null) {
                        appendAsciiCache(line);
                    } else {
                        // 流已结束
                        break;
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted() && mIsConnected) {
                        LLog.e(TAG, "ASCII接收错误: " + e.getMessage());
                        if (mListener != null) {
                            mListener.onReceiveError(e);
                        }
                    }
                    break;
                }
            }
        }, "SerialAsciiReceiver-" + mPortPath);
        mReceiverThread.start();
    }

    /**
     * 聚合 HEX 数据（短时间内多包合并回调）
     */
    private void appendHexCache(String hexStr) {
        synchronized (mHexReceiveCache) {
            mHexReceiveCache.append(hexStr);
        }
        mHandler.removeMessages(MSG_HEX_DATA_READY);
        mHandler.sendEmptyMessageDelayed(MSG_HEX_DATA_READY, DELAY_HEX_AGGREGATE_MS);
    }

    /**
     * 聚合 ASCII 数据（短时间内多行合并回调）
     */
    private void appendAsciiCache(String line) {
        synchronized (mAsciiReceiveCache) {
            mAsciiReceiveCache.append(line);
        }
        mHandler.removeMessages(MSG_ASCII_DATA_READY);
        mHandler.sendEmptyMessageDelayed(MSG_ASCII_DATA_READY, DELAY_ASCII_AGGREGATE_MS);
    }

    // ==================== 状态查询 ====================

    /**
     * 串口是否已连接
     */
    public boolean isConnected() {
        return mIsConnected;
    }

    /**
     * 获取当前串口路径
     */
    public String getPortPath() {
        return mPortPath;
    }

    /**
     * 获取当前波特率
     */
    public int getBaudRate() {
        return mBaudRate;
    }

    /**
     * 获取通信模式
     */
    public SerialType getSerialType() {
        return mSerialType;
    }

    // ==================== HEX 工具方法 ====================

    /**
     * 字节数组转换为十六进制字符串
     *
     * @param data   字节数组
     * @param length 要转换的长度
     * @return 大写十六进制字符串，如 "FF01A3"
     */
    public static String bytesToHexString(byte[] data, int length) {
        if (data == null || data.length == 0) {
            return "";
        }
        if (length <= 0 || length > data.length) {
            length = data.length;
        }
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            int value = data[i] & 0xFF;
            if (value < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(value));
        }
        return sb.toString().toUpperCase();
    }

    /**
     * 字节数组转换为十六进制字符串（全部转换）
     *
     * @param data 字节数组
     * @return 大写十六进制字符串
     */
    public static String bytesToHexString(byte[] data) {
        return bytesToHexString(data, data != null ? data.length : 0);
    }

    /**
     * 十六进制字符串转换为带空格的显示格式
     *
     * @param hexStr 十六进制字符串，如 "FF01A3"
     * @return 带空格格式，如 "FF 01 A3"
     */
    public static String hexStringToDisplayFormat(String hexStr) {
        if (hexStr == null || hexStr.length() < 2) {
            return hexStr == null ? "" : hexStr;
        }
        StringBuilder sb = new StringBuilder(hexStr.length() + hexStr.length() / 2);
        for (int i = 0; i < hexStr.length(); i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(hexStr, i, Math.min(i + 2, hexStr.length()));
        }
        return sb.toString();
    }

    /**
     * 十六进制字符串转换为字节数组
     *
     * @param hexString 十六进制字符串（长度必须为偶数，不含空格）
     * @return 字节数组
     * @throws IllegalArgumentException 输入为空或长度不为偶数
     */
    public static byte[] hexStringToBytes(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            throw new IllegalArgumentException("hexString must not be null or empty");
        }
        // 去除可能的空格
        hexString = hexString.replaceAll("\\s", "");
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("hexString length must be even, got: " + hexString.length());
        }

        final byte[] result = new byte[hexString.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hexString.charAt(i * 2), 16);
            int low = Character.digit(hexString.charAt(i * 2 + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid hex character at position " + (i * 2));
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    // ==================== Handler（主线程回调） ====================

    /**
     * 串口消息处理器（使用 WeakReference 避免内存泄漏）
     */
    private static class SerialHandler extends Handler {
        private final WeakReference<LSerialUtil> mRef;

        SerialHandler(LSerialUtil serialUtil) {
            super(Looper.getMainLooper());
            mRef = new WeakReference<>(serialUtil);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            LSerialUtil serial = mRef.get();
            if (serial == null || serial.mListener == null) {
                return;
            }

            switch (msg.what) {
                case MSG_HEX_DATA_READY: {
                    String data;
                    synchronized (serial.mHexReceiveCache) {
                        data = serial.mHexReceiveCache.toString();
                        serial.mHexReceiveCache.setLength(0);
                    }
                    serial.mListener.onDataReceived(data);
                    break;
                }
                case MSG_ASCII_DATA_READY: {
                    String data;
                    synchronized (serial.mAsciiReceiveCache) {
                        data = serial.mAsciiReceiveCache.toString();
                        serial.mAsciiReceiveCache.setLength(0);
                    }
                    serial.mListener.onDataReceived(data);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ==================== 回调接口 ====================

    /**
     * 串口事件回调接口
     * <p>
     * 推荐实现新方法名（onOpenError / onReceiveError / onSendError / onDataReceived），
     * 旧方法名（startError / receiverError / sendError / getReceiverStr）已标记为 @Deprecated，
     * 所有方法均有空的默认实现，可按需覆盖。
     */
    public interface OnSerialListener {

        // ==================== 推荐方法（新命名） ====================

        /**
         * 串口打开失败
         *
         * @param portPath 串口路径
         * @param e        异常信息（可为 null）
         */
        default void onOpenError(String portPath, @Nullable Exception e) {
            // 默认委托到旧方法，以兼容已有实现
            startError();
        }

        /**
         * 数据接收异常
         *
         * @param e 异常信息
         */
        default void onReceiveError(@Nullable Exception e) {
            receiverError();
        }

        /**
         * 数据发送异常
         *
         * @param e 异常信息
         */
        default void onSendError(@Nullable Exception e) {
            sendError();
        }

        /**
         * 接收到数据（已在主线程回调）
         *
         * @param data HEX模式下为十六进制字符串，ASCII模式下为文本字符串
         */
        default void onDataReceived(String data) {
            getReceiverStr(data);
        }

        // ==================== 旧方法名（兼容保留） ====================

        /** @deprecated 使用 {@link #onOpenError(String, Exception)} 代替 */
        @Deprecated
        default void startError() { }

        /** @deprecated 使用 {@link #onReceiveError(Exception)} 代替 */
        @Deprecated
        default void receiverError() { }

        /** @deprecated 使用 {@link #onSendError(Exception)} 代替 */
        @Deprecated
        default void sendError() { }

        /** @deprecated 使用 {@link #onDataReceived(String)} 代替 */
        @Deprecated
        default void getReceiverStr(String str) { }
    }

    // ==================== 内部工具方法 ====================

    private void stopReceiverThread() {
        if (mReceiverThread != null) {
            mReceiverThread.interrupt();
            try {
                mReceiverThread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mReceiverThread = null;
        }
    }

    private void closeStreams() {
        try {
            if (mPrintWriter != null) {
                mPrintWriter.close();
                mPrintWriter = null;
            }
        } catch (Exception e) {
            LLog.e(TAG, "关闭PrintWriter失败: " + e.getMessage());
        }

        try {
            if (mBufferedReader != null) {
                mBufferedReader.close();
                mBufferedReader = null;
            }
        } catch (Exception e) {
            LLog.e(TAG, "关闭BufferedReader失败: " + e.getMessage());
        }

        mOutputStream = null;
        mInputStream = null;
    }

    private void closeSerialPort() {
        if (mSerialPort != null) {
            mSerialPort.close();
            mSerialPort = null;
        }
    }

    private void clearReceiveCache() {
        synchronized (mHexReceiveCache) {
            mHexReceiveCache.setLength(0);
        }
        synchronized (mAsciiReceiveCache) {
            mAsciiReceiveCache.setLength(0);
        }
    }
}
