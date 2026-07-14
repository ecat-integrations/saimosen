package com.ecat.integration.SaimosenIntegration;

import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;
import com.ecat.integration.ModbusIntegration.Tools;

/**
 * SMS O3/CO 等分析仪 float 段端序：小端字节交换（C D A B）。
 * <p>
 * 读路径使用 modbus {@link Tools#convertLittleEndianByteSwapToFloat}；
 * 写路径为上述方法的逆变换（modbus Tools 未提供 floatToShorts 对称方法，故放在 saimosen 集成内）。
 */
public final class SmsLittleEndianByteSwapEndianConverter implements EndianConverter {

    public static final SmsLittleEndianByteSwapEndianConverter INSTANCE =
            new SmsLittleEndianByteSwapEndianConverter();

    private SmsLittleEndianByteSwapEndianConverter() {
    }

    @Override
    public short[] floatToShorts(float value) {
        int intBits = Float.floatToIntBits(value);
        byte b0 = (byte) ((intBits >> 8) & 0xFF);
        byte b1 = (byte) (intBits & 0xFF);
        byte b2 = (byte) ((intBits >> 16) & 0xFF);
        byte b3 = (byte) ((intBits >> 24) & 0xFF);
        short wordAtEvenRegister = (short) ((b2 << 8) | (b3 & 0xFF));
        short wordAtOddRegister = (short) ((b0 << 8) | (b1 & 0xFF));
        return new short[] { wordAtEvenRegister, wordAtOddRegister };
    }

    @Override
    public float shortsToFloat(short word1, short word2) {
        // ModbusFloatAttribute.updateValue(reg[i*2], reg[i*2+1]) 与 O3Device 读段一致
        return Tools.convertLittleEndianByteSwapToFloat(word2, word1);
    }

    @Override
    public short[] intToShorts(int value) {
        return floatToShorts(Float.intBitsToFloat(value));
    }

    @Override
    public int shortsToInt(short word1, short word2) {
        return Float.floatToIntBits(shortsToFloat(word1, word2));
    }

    @Override
    public short intToShort(int value) {
        return Tools.convertIntToShortBigEndian(value);
    }

    @Override
    public int shortToInt(short value) {
        return Tools.convertShortToIntBigEndian(value);
    }
}
