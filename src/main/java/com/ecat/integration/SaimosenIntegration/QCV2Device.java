package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.Unit.CurrentUnit;
import com.ecat.core.State.Unit.PowerUnit;
import com.ecat.core.State.Unit.VoltageUnit;

/**
 * SMS8910 质控仪完整协议 V2（寄存器 0~244）。
 * <p>
 * 在 {@link QCDevice} 完整协议 V1（0~232）基础上，通过第三块 Modbus 读取扩展段 233~244，
 * 接入质控仪内置智能稳压电源四路 U/I/P（功能码 0x03，只读，U16）。
 *
 * @version V2.0
 */
public class QCV2Device extends QCDevice {

    /** 协议 V2 扩展段起始地址 */
    private static final int POWER_SUPPLY_BLOCK_START = 233;
    /** 第1~4路 U/I/P 共 12 个 U16 寄存器 */
    private static final int POWER_SUPPLY_BLOCK_COUNT = 12;

    public QCV2Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    protected int getThirdBlockStart() {
        return POWER_SUPPLY_BLOCK_START;
    }

    @Override
    protected int getThirdBlockRegisterCount() {
        return POWER_SUPPLY_BLOCK_COUNT;
    }

    @Override
    protected void registerExtendedAttributeMap() {
        // 第1~4路电压 U — 地址 233~236（寄存器值 /10 为显示电压，与独立稳压电源一致）
        attributeMap.put(233, new AttributeInfo("voltage_l1", AttributeClass.VOLTAGE, "第1路U",
                ModbusDataType.U16X10, 1, VoltageUnit.VOLT, false, 1));
        attributeMap.put(234, new AttributeInfo("voltage_l2", AttributeClass.VOLTAGE, "第2路U",
                ModbusDataType.U16X10, 1, VoltageUnit.VOLT, false, 1));
        attributeMap.put(235, new AttributeInfo("voltage_l3", AttributeClass.VOLTAGE, "第3路U",
                ModbusDataType.U16X10, 1, VoltageUnit.VOLT, false, 1));
        attributeMap.put(236, new AttributeInfo("voltage_l4", AttributeClass.VOLTAGE, "第4路U",
                ModbusDataType.U16X10, 1, VoltageUnit.VOLT, false, 1));

        // 第1~4路电流 I — 地址 237~240
        attributeMap.put(237, new AttributeInfo("current_l1", AttributeClass.CURRENT, "第1路I",
                ModbusDataType.U16, 1, CurrentUnit.AMPERE, false, 2));
        attributeMap.put(238, new AttributeInfo("current_l2", AttributeClass.CURRENT, "第2路I",
                ModbusDataType.U16, 1, CurrentUnit.AMPERE, false, 2));
        attributeMap.put(239, new AttributeInfo("current_l3", AttributeClass.CURRENT, "第3路I",
                ModbusDataType.U16, 1, CurrentUnit.AMPERE, false, 2));
        attributeMap.put(240, new AttributeInfo("current_l4", AttributeClass.CURRENT, "第4路I",
                ModbusDataType.U16, 1, CurrentUnit.AMPERE, false, 2));

        // 第1~4路功率 P — 地址 241~244
        attributeMap.put(241, new AttributeInfo("power_l1", AttributeClass.POWER, "第1路P",
                ModbusDataType.U16, 1, PowerUnit.WATT, false));
        attributeMap.put(242, new AttributeInfo("power_l2", AttributeClass.POWER, "第2路P",
                ModbusDataType.U16, 1, PowerUnit.WATT, false));
        attributeMap.put(243, new AttributeInfo("power_l3", AttributeClass.POWER, "第3路P",
                ModbusDataType.U16, 1, PowerUnit.WATT, false));
        attributeMap.put(244, new AttributeInfo("power_l4", AttributeClass.POWER, "第4路P",
                ModbusDataType.U16, 1, PowerUnit.WATT, false));
    }
}
