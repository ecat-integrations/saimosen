package com.ecat.integration.SaimosenIntegration;

/**
 * 赛默森 SMS8910 质控仪协议型号常量。
 */
public final class SaimosenQCModels {

    /** 完整协议 V1（寄存器 0~232） */
    public static final String SMS8910 = "SMS8910";

    /** 完整协议 V2（寄存器 0~277，含智能稳压电源与 V2 追加参数） */
    public static final String SMS8910V2 = "SMS8910V2";

    private SaimosenQCModels() {
    }
}
