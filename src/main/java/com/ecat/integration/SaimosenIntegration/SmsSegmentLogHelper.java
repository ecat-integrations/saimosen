package com.ecat.integration.SaimosenIntegration;

import java.util.Locale;

import com.ecat.core.Utils.Log;

/**
 * 统一格式化 Saimosen 四参数设备 Modbus 段日志：原始寄存器/字节 + 协议转换后的显示值。
 */
final class SmsSegmentLogHelper {

    private SmsSegmentLogHelper() {
    }

    static void logFloatSegment(Log log, String deviceType, String deviceId,
                                String segment, int startAddress, short[] raw,
                                String[] fieldNames, double[] values) {
        if (!log.isInfoEnabled() || raw == null || values == null) {
            return;
        }
        StringBuilder body = new StringBuilder();
        int count = Math.min(fieldNames != null ? fieldNames.length : 0, values.length);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                body.append("; ");
            }
            body.append(formatFloatField(fieldNames[i], raw, i, values[i]));
        }
        log.info(formatSegmentHeader(deviceType, deviceId, segment, startAddress, raw)
                + " parsed={" + body + "}");
    }

    static void logU16Segment(Log log, String deviceType, String deviceId,
                              String segment, int startAddress, short[] raw,
                              String[] fieldNames, double[] displayValues) {
        if (!log.isInfoEnabled() || raw == null || displayValues == null) {
            return;
        }
        StringBuilder body = new StringBuilder();
        int count = Math.min(fieldNames != null ? fieldNames.length : 0, displayValues.length);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                body.append("; ");
            }
            body.append(formatU16Field(fieldNames[i], raw, i, displayValues[i]));
        }
        log.info(formatSegmentHeader(deviceType, deviceId, segment, startAddress, raw)
                + " parsed={" + body + "}");
    }

    static void logScalarSegment(Log log, String deviceType, String deviceId,
                                 String segment, int startAddress, short[] raw,
                                 String fieldName, double displayValue) {
        if (!log.isInfoEnabled() || raw == null || raw.length == 0) {
            return;
        }
        log.info(formatSegmentHeader(deviceType, deviceId, segment, startAddress, raw)
                + " parsed={" + formatU16Field(fieldName, raw, 0, displayValue) + "}");
    }

    private static String formatSegmentHeader(String deviceType, String deviceId,
                                              String segment, int startAddress, short[] raw) {
        return String.format(Locale.ROOT,
                "%s [%s] %s@0x%X (%d regs) raw_regs=[%s] raw_bytes=[%s] |",
                deviceType, deviceId, segment, startAddress, raw.length,
                formatRegisters(raw), formatBytes(raw));
    }

    private static String formatFloatField(String name, short[] raw, int fieldIndex, double value) {
        int regIndex = fieldIndex * 2;
        if (regIndex + 1 < raw.length) {
            return String.format(Locale.ROOT, "%s(regs=%s,%s bytes=%s)=%s",
                    name,
                    formatRegisterHex(raw[regIndex]),
                    formatRegisterHex(raw[regIndex + 1]),
                    formatRegisterPairBytes(raw[regIndex], raw[regIndex + 1]),
                    formatValue(value));
        }
        return name + "=" + formatValue(value);
    }

    private static String formatU16Field(String name, short[] raw, int index, double displayValue) {
        if (index < raw.length) {
            return String.format(Locale.ROOT, "%s(reg=%s bytes=%s)=%s",
                    name,
                    formatRegisterHex(raw[index]),
                    formatRegisterBytes(raw[index]),
                    formatValue(displayValue));
        }
        return name + "=" + formatValue(displayValue);
    }

    static String formatRegisterHex(short reg) {
        return String.format(Locale.ROOT, "0x%04X", reg & 0xFFFF);
    }

    static String formatRegisters(short[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatRegisterHex(data[i]));
        }
        return sb.toString();
    }

    static String formatBytes(short[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(formatRegisterBytes(data[i]));
        }
        return sb.toString();
    }

    private static String formatRegisterBytes(short reg) {
        int v = reg & 0xFFFF;
        return String.format(Locale.ROOT, "%02X %02X", (v >> 8) & 0xFF, v & 0xFF);
    }

    private static String formatRegisterPairBytes(short highReg, short lowReg) {
        return formatRegisterBytes(highReg) + ' ' + formatRegisterBytes(lowReg);
    }

    private static String formatValue(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        if (Math.abs(value - Math.rint(value)) < 1e-9 && Math.abs(value) < 1e15) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.6g", value);
    }
}
