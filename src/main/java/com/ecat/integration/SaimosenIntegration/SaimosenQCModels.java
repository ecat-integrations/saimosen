package com.ecat.integration.SaimosenIntegration;

/**
 * 赛默森 SMS8910 质控仪协议型号常量。
 */
public final class SaimosenQCModels {

    /** 完整协议 V1（寄存器 0~232） */
    public static final String SMS8910 = "SMS8910";

    /** 完整协议 V2（寄存器 0~273，含智能稳压电源四路 U/I/P） */
    public static final String SMS8910V2 = "SMS8910V2";

    private SaimosenQCModels() {
    }
}
