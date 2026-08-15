package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.Unit.CurrentUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.core.State.Unit.PowerUnit;
import com.ecat.core.State.Unit.RatioUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.core.State.UnitInfo;

/**
 * SMS8910 质控仪完整协议 V2（寄存器 0~283）。
 * <p>
 * 在 {@link QCDevice} 完整协议 V1（0~232）基础上，通过第三块 Modbus 读取扩展段 233~283，
 * 嵌入与 {@link SmartPowerStabilizer} 相同的智能稳压电源协议（系数/单位/读写与独立设备一致）：
 * <ul>
 *   <li>四路 U/I/P：233~244。独立设备为 I(0~3)/U(4~7)/P(8~11)，本协议按质控仪表排列为 U/I/P</li>
 *   <li>温湿度及保护参数：255~283 = 独立设备 12~40 + 243（245~254 为质控仪保留空隙）</li>
 * </ul>
 *
 * @version V2.1
 */
public class QCV2Device extends QCDevice {

    /** 协议 V2 扩展段起始地址 */
    private static final int POWER_SUPPLY_BLOCK_START = 233;
    /** 233~283 共 51 个 U16 寄存器（含 245~254 保留空隙） */
    private static final int POWER_SUPPLY_BLOCK_COUNT = 51;

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
        // 与 SmartPowerStabilizer 一致：U÷10、I÷100、P÷100（kW）
        // 第1~4路U
        putChannelAttrs(233, "voltage_l", AttributeClass.VOLTAGE, "U",
                ModbusDataType.U16X10, VoltageUnit.VOLT, false, 2);
        // 第1~4路I
        putChannelAttrs(237, "current_l", AttributeClass.CURRENT, "I",
                ModbusDataType.U16X100, CurrentUnit.AMPERE, false, 2);
        // 第1~4路P
        putChannelAttrs(241, "power_l", AttributeClass.POWER, "P",
                ModbusDataType.U16X100, PowerUnit.KILOWATT, false, 2);

        // 采集温度/湿度 — 独立设备 12/13，本协议 255/256，系数 10
        attributeMap.put(255, new AttributeInfo("temperature", AttributeClass.TEMPERATURE, "采集温度值",
                ModbusDataType.U16X10, 1, TemperatureUnit.CELSIUS, false, 2));
        attributeMap.put(256, new AttributeInfo("humidity", AttributeClass.HUMIDITY, "采集湿度值",
                ModbusDataType.U16X10, 1, RatioUnit.PERCENT, false, 2));

        // 第1~4路继电器 — 独立设备 14~17，本协议 257~260，0=跳闸，1=合闸
        putChannelAttrs(257, "relay_l", AttributeClass.STATUS, "继电器状态",
                ModbusDataType.U16X1, null, true, 0);

        putChannelAttrs(261, "temp_alarm_high_l", AttributeClass.TEMPERATURE, "温度异常上限",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, true, 2);
        putChannelAttrs(265, "temp_alarm_low_l", AttributeClass.TEMPERATURE, "温度异常下限",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, true, 2);
        putChannelAttrs(269, "startup_delay_l", AttributeClass.TIME, "开机启动延时",
                ModbusDataType.U16X1, NoConversionUnit.of("s", "s"), true, 0);
        putChannelAttrs(273, "temp_trip_high_l", AttributeClass.TEMPERATURE, "温度跳闸上限",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, true, 2);
        putChannelAttrs(277, "over_temp_protection_l", AttributeClass.STATUS, "超温保护是否启动",
                ModbusDataType.U16X1, null, true, 0);

        attributeMap.put(281, new AttributeInfo("temp_humidity_comm_status", AttributeClass.STATUS,
                "温湿度传感器通讯状态", ModbusDataType.U16X1, 1, null, false, 0));
        attributeMap.put(282, new AttributeInfo("electric_param_comm_status", AttributeClass.STATUS,
                "电参数模块通讯状态", ModbusDataType.U16X1, 1, null, false, 0));
        // 质控仪协议表标明 03/06 可写；独立稳压器该寄存器只读
        attributeMap.put(283, new AttributeInfo("device_address", AttributeClass.STATUS,
                "设备地址", ModbusDataType.U16X1, 1, null, true, 0));
    }

    private void putChannelAttrs(int startAddr, String idPrefix, AttributeClass cls, String nameSuffix,
            ModbusDataType type, UnitInfo unit, boolean writable, int precision) {
        for (int i = 1; i <= 4; i++) {
            attributeMap.put(startAddr + i - 1, new AttributeInfo(
                    idPrefix + i, cls, "第" + i + "路" + nameSuffix,
                    type, 1, unit, writable, precision));
        }
    }
}
