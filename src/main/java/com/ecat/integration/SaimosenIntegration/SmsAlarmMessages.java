package com.ecat.integration.SaimosenIntegration;

/**
 * Saimosen 四参数仪器报警位定义（bit0 起依次为各报警项，1=异常）。
 */
final class SmsAlarmMessages {

    static final String[] SO2_ACTIVE = {
            "测量电压异常",
            "样气压力异常",
            "反应室温度异常",
            "样气流量异常",
            "泵压力异常",
            "浓度斜率异常",
            "浓度截距异常",
            "机箱温度异常",
            "12V电压异常",
            "15V电压异常",
            "5V电压异常",
            "3.3V电压异常"
    };

    static final String[] CO_ACTIVE = {
            "光室温度异常",
            "相关轮温度异常",
            "涤除器温度异常",
            "样气流量异常",
            "样气压力异常",
            "泵压力异常",
            "12V电压异常",
            "15V电压异常",
            "5V电压异常",
            "3.3V电压异常"
    };

    static final String[] NOX_ACTIVE = {
            "样气压力异常",
            "样气温度异常",
            "钼炉温度异常",
            "PMT温度异常",
            "反应室压力异常",
            "臭氧流量异常",
            "样气流量异常",
            "零点电压异常",
            "机箱温度异常",
            "12V电压异常",
            "15V电压异常",
            "5V电压异常",
            "3.3V电压异常"
    };

    static final String[] O3_ACTIVE = {
            "测量电压异常",
            "参比电压异常",
            "样气压力异常",
            "样气温度异常",
            "样气流量报警",
            "浓度斜率异常",
            "浓度截距异常",
            "LED电流异常",
            "机箱温度异常",
            "泵压力异常",
            "15V电压异常报警",
            "12V电压异常",
            "3.3V电压异常",
            "5V电压异常"
    };

    private SmsAlarmMessages() {
    }
}
