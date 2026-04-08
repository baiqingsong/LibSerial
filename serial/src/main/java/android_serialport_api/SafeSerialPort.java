package android_serialport_api;

import java.io.File;
import java.io.IOException;

/**
 * SerialPort 的安全包装类，提供与 SerialPort 相同的 API。
 */
public class SafeSerialPort extends SerialPort {

    public SafeSerialPort(File device, int baudrate, int dataBits, int stopBits, char parity)
            throws SecurityException, IOException {
        super(device, baudrate, dataBits, stopBits, parity);
    }
}
