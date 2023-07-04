package android_serialport_api;

import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SerialPort {
    private static final String TAG = "SerialPort";

    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;
    /***
     * 构造方法
     * @param device 串口文件
     * @param baudrate 波特率
     * @param dataBits 数据位
     * @param stopBits 停止位
     * @param parity   校验位
     */
    public SerialPort(File device, int baudrate, int dataBits,int stopBits,char parity)
            throws SecurityException, IOException {

        /* Check access permission */
        if (!device.canRead() || !device.canWrite()) {
            try {
                /* Missing read/write permission, trying to chmod the file */
                Process su;
                su = Runtime.getRuntime().exec("/system/bin/su");
                String cmd = "chmod 777 " + device.getAbsolutePath() + "\n"
                        + "exit\n";
                su.getOutputStream().write(cmd.getBytes());
                if ((su.waitFor() != 0) || !device.canRead()
                        || !device.canWrite()) {
                    throw new SecurityException();
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new SecurityException();
            }
        }

        FileDescriptor mFd = open(device.getAbsolutePath(), baudrate, dataBits,stopBits,parity);
        if (mFd == null) {
            Log.e(TAG, "native open returns null");
            throw new IOException();
        }
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    // Getters and setters
    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }


    // 调用JNI中 打开方法的声明
    private native static FileDescriptor open(String path, int baudrate,
                                              int dataBits,int stopBits,char parity);

    public native void close();

    static {
        System.loadLibrary("serial_port");
    }
}