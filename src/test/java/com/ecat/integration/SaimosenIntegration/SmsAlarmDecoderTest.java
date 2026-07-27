package com.ecat.integration.SaimosenIntegration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SmsAlarmDecoderTest {

    @Test
    public void noAlarmReturnsEmpty() {
        assertEquals("", SmsAlarmDecoder.decodeActiveAlarms(0, SmsAlarmMessages.SO2_ACTIVE));
    }

    @Test
    public void singleBitAlarm() {
        assertEquals("测量电压异常", SmsAlarmDecoder.decodeActiveAlarms(0x0001, SmsAlarmMessages.SO2_ACTIVE));
    }

    @Test
    public void multipleBitAlarmsJoined() {
        assertEquals("测量电压异常、样气压力异常",
                SmsAlarmDecoder.decodeActiveAlarms(0x0003, SmsAlarmMessages.SO2_ACTIVE));
    }

    @Test
    public void noxAlarmBit5() {
        assertEquals("臭氧流量异常", SmsAlarmDecoder.decodeActiveAlarms(0x0020, SmsAlarmMessages.NOX_ACTIVE));
    }
}
