package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.*;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 通用气体设备命令属性，支持灵活配置命令模板和返回判断规则。
 * 支持通过设备属性（如CALIBRATION_CONCENTRATION）动态拼接命令参数。
 * 命令下发采用事务策略，确保原子性。
 * 使用工厂模式 + 策略模式支持不同气体类型的校准配置。
 */
public class GasDeviceCommandAttribute extends StringCommandAttribute {

    /**
     * 命令类型枚举，六类校准命令
     */
    public enum CommandType {
        ZERO_CALIBRATION_START,   // 零点校准开始 (0x3E8)
        ZERO_CALIBRATION_CONFIRM, // 零点校准确认 (0x3E9)
        ZERO_CALIBRATION_CANCEL,  // 零点校准取消 (0x3EA)
        SPAN_CALIBRATION_START,   // 跨度校准开始 (0x3EB)
        SPAN_CALIBRATION_CONFIRM, // 跨度校准确认 (0x3EC)
        SPAN_CALIBRATION_CANCEL   // 跨度校准取消 (0x3ED)
    }

    /**
     * 命令配置类，包含Modbus地址、写入值、命令类型
     */
    public static class CommandConfig {
        public int modbusAddress;  // Modbus寄存器地址
        public int writeValue;     // 写入的值
        public CommandType type;   // 命令类型
        public boolean needsConcentration; // 是否需要校准浓度参数
        
        public CommandConfig(int modbusAddress, int writeValue, CommandType type, boolean needsConcentration) {
            this.modbusAddress = modbusAddress;
            this.writeValue = writeValue;
            this.type = type;
            this.needsConcentration = needsConcentration;
        }
    }

    /**
     * 气体命令配置工厂接口
     */
    public abstract static class GasCommandConfigFactory {
        public abstract Map<String, CommandConfig> createCommandConfigs();
        public abstract String getGasType();
    }

    /**
     * CO设备命令配置工厂
     */
    public static class COCommandConfigFactory extends GasCommandConfigFactory {
        @Override
        public Map<String, CommandConfig> createCommandConfigs() {
            Map<String, CommandConfig> configs = new LinkedHashMap<>();
            configs.put("zero_calibration_start", new CommandConfig(0x3E8, 0, CommandType.ZERO_CALIBRATION_START, false));
            configs.put("zero_calibration_confirm", new CommandConfig(0x3E9, 0, CommandType.ZERO_CALIBRATION_CONFIRM, false));
            configs.put("zero_calibration_cancel", new CommandConfig(0x3EA, 0, CommandType.ZERO_CALIBRATION_CANCEL, false));
            configs.put("span_calibration_start", new CommandConfig(0x3EB, 40, CommandType.SPAN_CALIBRATION_START, true));
            configs.put("span_calibration_confirm", new CommandConfig(0x3EC, 40, CommandType.SPAN_CALIBRATION_CONFIRM, false));
            configs.put("span_calibration_cancel", new CommandConfig(0x3ED, 0, CommandType.SPAN_CALIBRATION_CANCEL, false));
            return configs;
        }

        @Override
        public String getGasType() {
            return "CO";
        }
    }

    /**
     * NO2设备命令配置工厂
     */
    public static class NO2CommandConfigFactory extends GasCommandConfigFactory {
        @Override
        public Map<String, CommandConfig> createCommandConfigs() {
            Map<String, CommandConfig> configs = new LinkedHashMap<>();
            configs.put("zero_calibration_start", new CommandConfig(0x3E8, 0, CommandType.ZERO_CALIBRATION_START, false));
            configs.put("zero_calibration_confirm", new CommandConfig(0x3E9, 0, CommandType.ZERO_CALIBRATION_CONFIRM, false));
            configs.put("zero_calibration_cancel", new CommandConfig(0x3EA, 0, CommandType.ZERO_CALIBRATION_CANCEL, false));
            configs.put("span_calibration_start", new CommandConfig(0x3EB, 400, CommandType.SPAN_CALIBRATION_START, true));
            configs.put("span_calibration_confirm", new CommandConfig(0x3EC, 400, CommandType.SPAN_CALIBRATION_CONFIRM, false));
            configs.put("span_calibration_cancel", new CommandConfig(0x3ED, 0, CommandType.SPAN_CALIBRATION_CANCEL, false));
            return configs;
        }

        @Override
        public String getGasType() {
            return "NO2";
        }
    }

    /**
     * SO2设备命令配置工厂
     */
    public static class SO2CommandConfigFactory extends GasCommandConfigFactory {
        @Override
        public Map<String, CommandConfig> createCommandConfigs() {
            Map<String, CommandConfig> configs = new LinkedHashMap<>();
            configs.put("zero_calibration_start", new CommandConfig(0x3E8, 0, CommandType.ZERO_CALIBRATION_START, false));
            configs.put("zero_calibration_confirm", new CommandConfig(0x3E9, 0, CommandType.ZERO_CALIBRATION_CONFIRM, false));
            configs.put("zero_calibration_cancel", new CommandConfig(0x3EA, 0, CommandType.ZERO_CALIBRATION_CANCEL, false));
            configs.put("span_calibration_start", new CommandConfig(0x3EB, 400, CommandType.SPAN_CALIBRATION_START, true));
            configs.put("span_calibration_confirm", new CommandConfig(0x3EC, 400, CommandType.SPAN_CALIBRATION_CONFIRM, false));
            configs.put("span_calibration_cancel", new CommandConfig(0x3ED, 0, CommandType.SPAN_CALIBRATION_CANCEL, false));
            return configs;
        }

        @Override
        public String getGasType() {
            return "SO2";
        }
    }

    /**
     * O3设备命令配置工厂
     */
    public static class O3CommandConfigFactory extends GasCommandConfigFactory {
        @Override
        public Map<String, CommandConfig> createCommandConfigs() {
            Map<String, CommandConfig> configs = new LinkedHashMap<>();
            configs.put("zero_calibration_start", new CommandConfig(0x3E8, 0, CommandType.ZERO_CALIBRATION_START, false));
            configs.put("zero_calibration_confirm", new CommandConfig(0x3E9, 0, CommandType.ZERO_CALIBRATION_CONFIRM, false));
            configs.put("zero_calibration_cancel", new CommandConfig(0x3EA, 0, CommandType.ZERO_CALIBRATION_CANCEL, false));
            configs.put("span_calibration_start", new CommandConfig(0x3EB, 400, CommandType.SPAN_CALIBRATION_START, true));
            configs.put("span_calibration_confirm", new CommandConfig(0x3EC, 400, CommandType.SPAN_CALIBRATION_CONFIRM, false));
            configs.put("span_calibration_cancel", new CommandConfig(0x3ED, 0, CommandType.SPAN_CALIBRATION_CANCEL, false));
            return configs;
        }

        @Override
        public String getGasType() {
            return "O3";
        }
    }

    private final Map<String, CommandConfig> commandConfigMap;
    private ModbusSource modbusSource;
    private GasCommandConfigFactory factory;
    // 设备引用，用于通知写入操作（防止竞态条件）
    private Object deviceInstance; // 使用Object类型避免循环依赖

    /**
     * 构造函数 - 使用工厂模式，支持I18n
     * @param attributeID 属性ID
     * @param attrClass 属性类型
     * @param factory 气体命令配置工厂
     */
    public GasDeviceCommandAttribute(String attributeID, AttributeClass attrClass, GasCommandConfigFactory factory) {
        super(attributeID, attrClass);
        this.factory = factory;

        // 使用工厂创建命令配置
        commandConfigMap = factory.createCommandConfigs();
        setCommands(new ArrayList<>(commandConfigMap.keySet()));
    }

    /**
     * 构造函数 - 兼容旧版本，默认使用CO配置
     * @param attributeID 属性ID
     * @param attrClass 属性类型
     */
    public GasDeviceCommandAttribute(String attributeID, AttributeClass attrClass) {
        this(attributeID, attrClass, new COCommandConfigFactory());
    }

    /**
     * 设置Modbus源
     * @param modbusSource Modbus源
     */
    public void setModbusSource(ModbusSource modbusSource) {
        this.modbusSource = modbusSource;
    }

    /**
     * 添加依赖属性
     * @param attribute 依赖的属性
     */
    public void addDependencyAttribute(AttributeBase<?> attribute) {
        if (attribute != null) {
            dependencyAttributes.add(attribute);
        }
    }

    /**
     * 设置设备实例引用（用于通知写入操作，防止竞态条件）
     * @param device 设备实例
     */
    public void setDeviceInstance(Object device) {
        this.deviceInstance = device;
    }

    /**
     * 发送命令，采用事务策略，确保命令下发和响应读取的原子性。
     * @param type 命令类型
     * @return 命令执行是否成功
     */
    @Override
    protected CompletableFuture<Boolean> sendCommandImpl(String type) {
        if (modbusSource == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        CommandConfig config = commandConfigMap.get(type);
        if (config == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 确定要写入的值
            final int writeValue;
            if (config.needsConcentration) {
                // 如果需要浓度参数，从依赖属性获取
                double concentration = getConcentrationFromDependency();
                writeValue = (int) concentration;
            } else {
                writeValue = config.writeValue;
            }
            
            return source.writeRegister(config.modbusAddress, writeValue).thenApply((response) -> {
                if (response == null || response.isException()) {
                    throw new RuntimeException("命令下发失败" + response.getExceptionMessage());
                }
                log.info("GasDeviceCommandAttribute - 校准命令执行成功: " + type +
                        ", 气体类型: " + factory.getGasType() +
                        ", 地址: 0x" + Integer.toHexString(config.modbusAddress).toUpperCase() +
                        ", 值: " + writeValue);
                
                // 如果写入的是校准浓度地址（0x3EB），通知设备以防止竞态条件
                if (config.modbusAddress == 0x3EB && config.needsConcentration && deviceInstance != null) {
                    try {
                        // 使用反射调用设备的 markCalibrationWrite 方法
                        java.lang.reflect.Method method = deviceInstance.getClass().getMethod("markCalibrationWrite", double.class);
                        method.invoke(deviceInstance, (double) writeValue);
                        log.debug("GasDeviceCommandAttribute - 已通知设备写入校准浓度: " + writeValue);
                    } catch (Exception e) {
                        // 如果设备不支持此方法，忽略错误（兼容性考虑）
                        log.debug("GasDeviceCommandAttribute - 设备不支持 markCalibrationWrite 方法: " + e.getMessage());
                    }
                }
                
                return true;
            });
        }).exceptionally(throwable -> {
            log.error("GasDeviceCommandAttribute - 校准命令执行失败: " + type + 
                    ", 气体类型: " + factory.getGasType() + ", 错误: " + throwable.getMessage());
            return false;
        });
    }
    
    /**
     * 从依赖属性获取校准浓度
     * @return 校准浓度值
     */
    private double getConcentrationFromDependency() {
        for (AttributeBase<?> attr : dependencyAttributes) {
            if (attr instanceof NumericAttribute) {
                NumericAttribute numAttr = (NumericAttribute) attr;
                if ("CALIBRATION_CONCENTRATION".equals(numAttr.getAttributeID())) {
                    return numAttr.getValue();
                }
            }
        }
        return 0.0; // 默认值
    }



    /**
     * 获取当前气体类型
     * @return 气体类型
     */
    public String getGasType() {
        return factory.getGasType();
    }

    /**
     * 获取当前工厂
     * @return 工厂实例
     */
    public GasCommandConfigFactory getFactory() {
        return factory;
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        return value;
    }
}
