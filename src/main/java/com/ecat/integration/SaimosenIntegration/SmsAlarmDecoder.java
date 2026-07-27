package com.ecat.integration.SaimosenIntegration;

/**
 * 按位解析 U16 报警寄存器，拼接当前激活的报警描述。
 */
final class SmsAlarmDecoder {

    private SmsAlarmDecoder() {
    }

    /**
     * @param alarmRegister U16 报警寄存器原始值
     * @param activeMessages 各 bit 对应报警文案（bit0 对应 index 0）
     * @return 无报警时返回空字符串；多报警以顿号拼接
     */
    static String decodeActiveAlarms(int alarmRegister, String[] activeMessages) {
        if (activeMessages == null || activeMessages.length == 0) {
            return "";
        }
        int bits = alarmRegister & 0xFFFF;
        if (bits == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < activeMessages.length; i++) {
            if ((bits & (1 << i)) == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(activeMessages[i]);
        }
        return sb.toString();
    }

    static String decodeActiveAlarms(double registerValue, String[] activeMessages) {
        return decodeActiveAlarms((int) registerValue, activeMessages);
    }
}
