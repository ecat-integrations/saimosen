package com.ecat.integration.SaimosenIntegration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.core.State.Unit.PowerUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusScalableFloatSRAttribute;
import com.ecat.integration.ModbusIntegration.EndianConverter.AbstractEndianConverter;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;

/**
 * 采样管加热器设备类，用于通过Modbus协议读取和控制采样管加热器设备
 * 支持温度控制、功率监测、湿度监测等功能
 * 
 * 寄存器地址映射（协议V1.0，V1.1适用）：
 * 0x0000: 样气湿度（实际值的10倍）- 只读
 * 0x0001: 样气温度（实际值的10倍）- 只读
 * 0x0002: 无意义 - 读写
 * 0x0003: 无意义 - 只读
 * 0x0004: 设备地址 - 读写
 * 0x0005: 样气流速（实际值的10倍）- 只读
 * 0x0006: 加热管实际温度（实际值的10倍）- 读写
 * 0x0007: 风机功率（实际值的10倍）- 只读
 * 0x0008: 加热带功率（实际值的10倍）- 只读
 * 0x0009: 未使用 - 只读
 * 0x000A: 加热管设置温度（设置值的10倍）- 读写
 * 
 * @version V1.1
 */
public class SampleTube extends SmsDeviceBase {
    // 寄存器块定义
    private static final Map<String, RegisterBlock> BLOCK_CONFIG = new HashMap<>();
    static {
        // 定义设备寄存器块（从地址0开始，共11个寄存器，地址0x0000-0x000A）
        BLOCK_CONFIG.put("DEFAULT", new RegisterBlock(0, 11));
    }

    // 大端模式转换器
    private BigEndianConverter bigConverter = AbstractEndianConverter.getBigEndianConverter();
    // 读取任务
    private ScheduledFuture<?> readFuture;

    public SampleTube(Map<String, Object> config) {
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
        super.release();
    }

    /**
     * 创建设备属性（根据协议文档）
     */
    private void createAttributes() {
        // 地址0x0000: 样气湿度（实际值的10倍）- 只读
        setAttribute(new ModbusScalableFloatSRAttribute(
                "humidity",
                AttributeClass.HUMIDITY,
                NoConversionUnit.of("%", "%"),
                NoConversionUnit.of("%", "%"),
                1,
                false,
                false,
                modbusSource,
                (short) 0,
                bigConverter,
                10
        ));

        // 地址0x0001: 样气温度（实际值的10倍）- 只读
        setAttribute(new ModbusScalableFloatSRAttribute(
                "sample_gas_temperature",
                AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                1,
                false,
                false,
                modbusSource,
                (short) 1,
                bigConverter,
                10
        ));

        // 地址0x0002: 无意义 - 读写（保留用于校准状态）
        setAttribute(new ModbusScalableFloatSRAttribute(
                "calibration_status",
                AttributeClass.STATUS,
                null,
                null,
                0,
                false,
                true,
                modbusSource,
                (short) 2,
                bigConverter,
                1
        ));

        // 地址0x0003: 无意义 - 只读（保留）
        setAttribute(new ModbusScalableFloatSRAttribute(
                "reserved_3",
                AttributeClass.STATUS,
                null,
                null,
                0,
                false,
                false,
                modbusSource,
                (short) 3,
                bigConverter,
                1
        ));

        // 地址0x0004: 设备地址 - 读写
        setAttribute(new ModbusScalableFloatSRAttribute(
                "device_address",
                AttributeClass.ADDRESS,
                null,
                null,
                0,
                false,
                true,
                modbusSource,
                (short) 4,
                bigConverter,
                1
        ));

        // 地址0x0005: 样气流速（实际值的10倍）- 只读
        setAttribute(new ModbusScalableFloatSRAttribute(
                "gas_flow_rate",
                AttributeClass.FLOW,
                LiterFlowUnit.L_PER_MINUTE,
                LiterFlowUnit.L_PER_MINUTE,
                1,
                false,
                false,
                modbusSource,
                (short) 5,
                bigConverter,
                10
        ));

        // 地址0x0006: 加热管实际温度（实际值的10倍）- 读写
        setAttribute(new ModbusScalableFloatSRAttribute(
                "heating_tube_actual_temp",
                AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                1,
                false,
                true,
                modbusSource,
                (short) 6,
                bigConverter,
                10
        ));

        // 地址0x0007: 风机功率（实际值的10倍）- 只读
        setAttribute(new ModbusScalableFloatSRAttribute(
                "fan_power",
                AttributeClass.POWER,
                PowerUnit.WATT,
                PowerUnit.WATT,
                1,
                false,
                false,
                modbusSource,
                (short) 7,
                bigConverter,
                10
        ));

        // 地址0x0008: 加热带功率（实际值的10倍）- 只读
        setAttribute(new ModbusScalableFloatSRAttribute(
                "heating_belt_power",
                AttributeClass.POWER,
                PowerUnit.WATT,
                PowerUnit.WATT,
                1,
                false,
                false,
                modbusSource,
                (short) 8,
                bigConverter,
                10
        ));

        // 地址0x0009: 未使用 - 只读
        setAttribute(new ModbusScalableFloatSRAttribute(
                "reserved_9",
                AttributeClass.STATUS,
                null,
                null,
                0,
                false,
                false,
                modbusSource,
                (short) 9,
                bigConverter,
                1
        ));

        // 地址0x000A: 加热管设置温度（设置值的10倍）- 读写
        setAttribute(new ModbusScalableFloatSRAttribute(
                "heating_tube_target_temp",
                AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                1,
                false,
                true,
                modbusSource,
                (short) 10,
                bigConverter,
                10
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
                            log.info("SampleTube - Data updated successfully");
                            return true;
                        } catch (Exception e) {
                            log.error("SampleTube parsing failed: " + e.getMessage());
                            getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                            publicAttrsState();
                            return false;
                        }
                    });
        });
    }

    /**
     * 解析寄存器数据（根据协议文档）
     */
    private void parseRegisters(short[] registers) {
        AttributeStatus status = AttributeStatus.NORMAL;
        
        // 地址0x0000: 样气湿度
        updateScalableAttribute("humidity", registers[0], status);

        // 地址0x0001: 样气温度
        updateScalableAttribute("sample_gas_temperature", registers[1], status);

        // 地址0x0002: 校准状态（无意义）
        updateScalableAttribute("calibration_status", registers[2], status);

        // 地址0x0003: 保留（无意义）
        updateScalableAttribute("reserved_3", registers[3], status);

        // 地址0x0004: 设备地址
        updateScalableAttribute("device_address", registers[4], status);

        // 地址0x0005: 样气流速
        updateScalableAttribute("gas_flow_rate", registers[5], status);

        // 地址0x0006: 加热管实际温度
        updateScalableAttribute("heating_tube_actual_temp", registers[6], status);

        // 地址0x0007: 风机功率
        updateScalableAttribute("fan_power", registers[7], status);

        // 地址0x0008: 加热带功率
        updateScalableAttribute("heating_belt_power", registers[8], status);

        // 地址0x0009: 未使用
        updateScalableAttribute("reserved_9", registers[9], status);

        // 地址0x000A: 加热管设置温度
        updateScalableAttribute("heating_tube_target_temp", registers[10], status);
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
     * 设置加热管目标温度（地址0x000A）
     * @param temperature 目标温度值（摄氏度）
     */
    public void setHeatingTubeTargetTemp(float temperature) {
        ModbusScalableFloatSRAttribute attr = (ModbusScalableFloatSRAttribute) getAttrs().get("heating_tube_target_temp");
        if (attr != null) {
            int value = (int) (temperature * 10); // 转换为寄存器值（实际值的10倍）
            ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
                return source.writeRegister(10, value)
                        .thenApply(response -> {
                            log.info("SampleTube - Heating tube target temperature set to: " + temperature + "°C");
                            return true;
                        })
                        .exceptionally(throwable -> {
                            log.error("SampleTube - Failed to set heating tube target temperature: " + throwable.getMessage());
                            return false;
                        });
            });
        }
    }

    /**
     * 设置加热管实际温度（地址0x0006）
     * @param temperature 实际温度值（摄氏度）
     */
    public void setHeatingTubeActualTemp(float temperature) {
        ModbusScalableFloatSRAttribute attr = (ModbusScalableFloatSRAttribute) getAttrs().get("heating_tube_actual_temp");
        if (attr != null) {
            int value = (int) (temperature * 10); // 转换为寄存器值（实际值的10倍）
            ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
                return source.writeRegister(6, value)
                        .thenApply(response -> {
                            log.info("SampleTube - Heating tube actual temperature set to: " + temperature + "°C");
                            return true;
                        })
                        .exceptionally(throwable -> {
                            log.error("SampleTube - Failed to set heating tube actual temperature: " + throwable.getMessage());
                            return false;
                        });
            });
        }
    }

    /**
     * 设置设备地址（地址0x0004）
     * @param address 设备地址（0-255）
     */
    public void setDeviceAddress(int address) {
        if (address < 0 || address > 255) {
            log.error("SampleTube - Invalid device address: " + address + " (must be 0-255)");
            return;
        }
        ModbusScalableFloatSRAttribute attr = (ModbusScalableFloatSRAttribute) getAttrs().get("device_address");
        if (attr != null) {
            ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
                return source.writeRegister(4, address)
                        .thenApply(response -> {
                            log.info("SampleTube - Device address set to: " + address);
                            return true;
                        })
                        .exceptionally(throwable -> {
                            log.error("SampleTube - Failed to set device address: " + throwable.getMessage());
                            return false;
                        });
            });
        }
    }

    /**
     * 设置校准状态（地址0x0002）
     * 根据协议文档，发送 00 06 00 02 00 00 将加热器置为校准状态
     * @param status 校准状态值（通常为0）
     */
    public void setCalibrationStatus(int status) {
        ModbusScalableFloatSRAttribute attr = (ModbusScalableFloatSRAttribute) getAttrs().get("calibration_status");
        if (attr != null) {
            ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
                return source.writeRegister(2, status)
                        .thenApply(response -> {
                            log.info("SampleTube - Calibration status set to: " + status);
                            return true;
                        })
                        .exceptionally(throwable -> {
                            log.error("SampleTube - Failed to set calibration status: " + throwable.getMessage());
                            return false;
                        });
            });
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
