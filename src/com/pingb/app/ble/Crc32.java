package com.pingb.app.ble;

/**
 * CRC32 校验（与 PixDotDot App 一致的实现）
 * 用于 BLE 发送帧的整包校验。
 */
public final class Crc32 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xEDB88320 : crc >>> 1;
            }
            TABLE[i] = crc;
        }
    }

    /** 计算 byte[] 的 CRC32（返回无符号 int，与 PixDotDot 一致） */
    public static int crc32(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = (crc >>> 8) ^ TABLE[(crc ^ data[i]) & 0xFF];
        }
        return crc ^ 0xFFFFFFFF;
    }

    /** 转小端 4 字节 */
    public static byte[] toLittleEndian(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    /** 小端 2 字节 */
    public static byte[] toLittleEndian16(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }

    /** 小端 2 字节 */
    public static int fromLittleEndian16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }
}
