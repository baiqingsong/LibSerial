package com.dawn.libserial;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.dawn.serial.LSerialUtil;

public class SerialService extends Service {
    private LSerialUtil serialUtil;
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startPort();
    }

    /**
     * 启动串口
     */
    private void startPort(){
        serialUtil = new LSerialUtil(1, 9600, 8, 2, 'N', LSerialUtil.SerialType.TYPE_HEX, new LSerialUtil.OnSerialListener() {
            @Override
            public void startError() {
                Log.i("dawn", "串口启动异常");
            }

            @Override
            public void receiverError() {
                Log.i("dawn", "串口接收异常");
            }

            @Override
            public void sendError() {
                Log.i("dawn", "串口发送异常");
            }

            @Override
            public void getReceiverStr(String str) {
                Log.i("dawn", "串口接收到的数据：" + str);
            }
        });
    }

    /**
     * 发送数据
     * @param hexStr 16进制字符串
     */
    private void sendMsg(String hexStr){
        serialUtil.sendHexMsg(hexStr);
    }
}
