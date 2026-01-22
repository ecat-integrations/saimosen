package com.ecat.integration.SaimosenIntegration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.Unit.CurrentUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.core.State.Unit.PowerUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusScalableFloatSRAttribute;
import com.ecat.integration.ModbusIntegration.EndianConverter.AbstractEndianConverter;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;

/**
 * 智能电力稳压器类，用于通过Modbus协议读取和控制电力稳压器设备
 * 
 * @version V1.0
 * @author coffee
 */
public class SmartPowerStabilizer extends SmsDeviceBase {
    // 寄存器块定义
    private static final Map<String, RegisterBlock> BLOCK_CONFIG = new HashMap<>();
    static {
        // 定义设备寄存器块（从地址0开始，共41个寄存器）
        BLOCK_CONFIG.put("DEFAULT", new RegisterBlock(0, 41));
    }

    // 大端模式转换器
    private BigEndianConverter bigConverter = AbstractEndianConverter.getBigEndianConverter();
    // 读取任务
    private ScheduledFuture<?> readFuture;



    public SmartPowerStabilizer(Map<String, Object> config) {
        super(config);
    }

    /**
     * 初始化设备
     */
    public void init() {
        super.init();
        createAttributes();
    }

    /**
     * 启动设备数据读取
     */
    public void start() {
        readFuture = getScheduledExecutor().scheduleWithFixedDelay(this::readRegisters, 0, 5, TimeUnit.SECONDS);
    }

    /**
     * 停止设备数据读取
     */
    public void stop() {
        if (readFuture != null) {
            readFuture.cancel(true);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();
    }

    /**
     * 创建设备属性
     */
    private void createAttributes() {
        // 电流相关属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "current_l" + i,
                    AttributeClass.CURRENT,
                    CurrentUnit.AMPERE,
                    CurrentUnit.AMPERE,
                    2,
                    false,
                    false,
                    modbusSource,
                    (short) (i - 1),
                    bigConverter,
                    100
            ));
        }

        // 电压相关属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "voltage_l" + i,
                    AttributeClass.VOLTAGE,
                    VoltageUnit.VOLT,
                    VoltageUnit.VOLT,
                    2,
                    false,
                    false,
                    modbusSource,
                    (short) (4 + i - 1),
                    bigConverter,
                    10
            ));
        }

        // 功率相关属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "power_l" + i,
                    AttributeClass.POWER,
                    PowerUnit.KILOWATT,
                    PowerUnit.KILOWATT,
                    2,
                    false,
                    false,
                    modbusSource,
                    (short) (8 + i - 1),
                    bigConverter,
                    100
            ));
        }

        // 温度和湿度属性
        setAttribute(new ModbusScalableFloatSRAttribute(
                "temperature",
                AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                2,
                false,
                false,
                modbusSource,
                (short) 12,
                bigConverter,
                10
        ));

        setAttribute(new ModbusScalableFloatSRAttribute(
                "humidity",
                AttributeClass.HUMIDITY,
                NoConversionUnit.of("%", "%"),
                NoConversionUnit.of("%", "%"),
                2,
                false,
                false,
                modbusSource,
                (short) 13,
                bigConverter,
                10
        ));

        // 继电器状态属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "relay_l" + i,
                    AttributeClass.STATUS,
                    null,
                    null,
                    0,
                    false,
                    true,
                    modbusSource,
                    (short) (14 + i - 1),
                    bigConverter,
                    1
            ));
        }

        // 温度异常上限属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "temp_alarm_high_l" + i,
                    AttributeClass.TEMPERATURE,
                    TemperatureUnit.CELSIUS,
                    TemperatureUnit.CELSIUS,
                    2,
                    false,
                    true,
                    modbusSource,
                    (short) (18 + i - 1),
                    bigConverter,
                    10
            ));
        }

        // 温度异常下限属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "temp_alarm_low_l" + i,
                    AttributeClass.TEMPERATURE,
                    TemperatureUnit.CELSIUS,
                    TemperatureUnit.CELSIUS,
                    2,
                    false,
                    true,
                    modbusSource,
                    (short) (22 + i - 1),
                    bigConverter,
                    10
            ));
        }

        // 开机启动延时属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "startup_delay_l" + i,
                    AttributeClass.TIME,
                    NoConversionUnit.of("s", "s"),
                    NoConversionUnit.of("s", "s"),
                    0,
                    false,
                    true,
                    modbusSource,
                    (short) (26 + i - 1),
                    bigConverter,
                    1
            ));
        }

        // 温度跳闸上限属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "temp_trip_high_l" + i,
                    AttributeClass.TEMPERATURE,
                    TemperatureUnit.CELSIUS,
                    TemperatureUnit.CELSIUS,
                    2,
                    false,
                    true,
                    modbusSource,
                    (short) (30 + i - 1),
                    bigConverter,
                    10
            ));
        }

        // 超温保护状态属性 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            setAttribute(new ModbusScalableFloatSRAttribute(
                    "over_temp_protection_l" + i,
                    AttributeClass.STATUS,
                    null,
                    null,
                    0,
                    false,
                    true,
                    modbusSource,
                    (short) (34 + i - 1),
                    bigConverter,
                    1
            ));
        }

        // 通讯状态属性
        setAttribute(new ModbusScalableFloatSRAttribute(
                "temp_humidity_comm_status",
                AttributeClass.STATUS,
                null,
                null,
                0,
                false,
                false,
                modbusSource,
                (short) 38,
                bigConverter,
                1
        ));

        setAttribute(new ModbusScalableFloatSRAttribute(
                "electric_param_comm_status",
                AttributeClass.STATUS,
                null,
                null,
                0,
                false,
                false,
                modbusSource,
                (short) 39,
                bigConverter,
                1
        ));

        // 设备地址属性
        setAttribute(new ModbusScalableFloatSRAttribute(
                "device_address",
                AttributeClass.STATUS,
                null,
                null,
                0,
                false,
                false,
                modbusSource,
                (short) 40,
                bigConverter,
                1
        ));
    }

    /**
     * 读取所有寄存器并解析数据
     */
    private void readRegisters() {
        if (!BLOCK_CONFIG.containsKey("DEFAULT")) {
            log.error("Unsupported device configuration for reading");
            return;
        }

        ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            RegisterBlock block = BLOCK_CONFIG.get("DEFAULT");
            return source.readHoldingRegisters(block.startAddress, block.registerCount)
                    .thenApply(response -> {
                        try {
                            short[] registers = response.getShortData();
                            parseRegisters(registers);
                            getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.NORMAL));
                            publicAttrsState();
                            log.info("SmartPowerStabilizer - Data updated successfully");
                            return true;
                        } catch (Exception e) {
                            log.error("SmartPowerStabilizer parsing failed: " + e.getMessage());
                            getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                            publicAttrsState();
                            return false;
                        }
                    });
        });
    }

    /**
     * 解析寄存器数据
     */
    private void parseRegisters(short[] registers) {
        AttributeStatus status = AttributeStatus.NORMAL;
        
        // 解析电流数据 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("current_l" + i, registers[i-1], status);
        }

        // 解析电压数据 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("voltage_l" + i, registers[4+i-1], status);
        }
        
        // 解析功率数据 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("power_l" + i, registers[8+i-1], status);
        }
        
        // 解析温度和湿度
        updateScalableAttribute("temperature", registers[12], status);
        updateScalableAttribute("humidity", registers[13], status);
        
        // 解析继电器状态 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("relay_l" + i, registers[14+i-1], status);
        }
        
        // 解析温度异常上限 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("temp_alarm_high_l" + i, registers[18+i-1], status);
        }
        
        // 解析温度异常下限 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("temp_alarm_low_l" + i, registers[22+i-1], status);
        }
        
        // 解析开机启动延时 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("startup_delay_l" + i, registers[26+i-1], status);
        }
        
        // 解析温度跳闸上限 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("temp_trip_high_l" + i, registers[30+i-1], status);
        }
        
        // 解析超温保护状态 (第1-4路)
        for (int i = 1; i <= 4; i++) {
            updateScalableAttribute("over_temp_protection_l" + i, registers[34+i-1], status);
        }
        
        // 解析通讯状态
        updateScalableAttribute("temp_humidity_comm_status", registers[38], status);
        updateScalableAttribute("electric_param_comm_status", registers[39], status);
        
        // 解析设备地址
        updateScalableAttribute("device_address", registers[40], status);
    }

    /**
     * 更新可缩放的浮点型属性值
     */
    private void updateScalableAttribute(String attrId, short data, AttributeStatus status) {
        ModbusScalableFloatSRAttribute attr = (ModbusScalableFloatSRAttribute) getAttrs().getOrDefault(attrId, null);
        if (attr != null) {
            attr.updateValue(data, status);
        }
    }


    /**
     * 寄存器块配置类
     */
    private static class RegisterBlock {
        int startAddress;   // 起始寄存器地址
        int registerCount;  // 读取寄存器数量

        RegisterBlock(int startAddress, int registerCount) {
            this.startAddress = startAddress;
            this.registerCount = registerCount;
        }
    }
}
