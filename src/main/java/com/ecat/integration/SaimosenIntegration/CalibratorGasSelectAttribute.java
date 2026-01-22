package com.ecat.integration.SaimosenIntegration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * CalibratorGasSelectAttribute class
 * 
 * 处理校准器气体选择属性，支持从预定义的气体列表中选择
 * 通过Modbus协议与设备交互，更新寄存器值
 * 
 * @Author coffee
 */
public class CalibratorGasSelectAttribute extends StringSelectAttribute {

    private ModbusSource modbusSource; // Modbus源
    private Short registerAddress; // 目标寄存器地址（0x46）

    public static final String M_GPTNO = "M_GPTNO"; // 模式
    public static final String M_GPTNO_O3 = "M_GPTNO_O3"; // 模式
    public static final String M_GPT = "M_GPT"; // 模式

    // 构造函数：传入选项列表和Modbus源
    public CalibratorGasSelectAttribute(String attributeID, AttributeClass attrClass,
            boolean valueChangeable, List<String> options, ModbusSource modbusSource, Short registerAddress) {
        super(attributeID, attrClass, valueChangeable, options);
        this.modbusSource = modbusSource;
        this.registerAddress = registerAddress;
        this.value = options.get(0); // 默认值
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        return value;
    }

    public boolean updateValue(Short value) {
        String gas = CodeConverter.TO_STRING.apply(value);
        if (gas != null) {
            return super.updateValue(gas);
        } else {
            return false;
        }
    }

    @Override
    public CompletableFuture<Boolean> selectOptionImp(String option) {
        if (!valueChangeable) {
            return  CompletableFuture.completedFuture(false);
        }
        // 转换
        Short newValue = CodeConverter.TO_SHORT.apply(option);
        if (newValue == null) {
            return CompletableFuture.completedFuture(false);
        }
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 写单个寄存器（0x89地址，值为registerValue）
            return source.writeRegister(registerAddress, newValue)
                    .thenCompose((response) -> {
                        if (response == null || response.isException()) {
                            throw new RuntimeException("命令下发失败" + response.getExceptionMessage());
                        }
                        return CompletableFuture.completedFuture(true);
                    });
        });
    }
}

class CodeConverter {
    private static final Map<String, Short> STRING_TO_SHORT = new HashMap<>();
    private static final Map<Short, String> SHORT_TO_STRING = new HashMap<>();

    static {
        // 初始化双向映射
        putMapping(AttributeClass.Choose.getName(),  (short) 0x00);
        putMapping(AttributeClass.SO2.getName(), (short) 0x01);
        putMapping(AttributeClass.NO.getName(), (short) 0x02);
        putMapping(AttributeClass.CO.getName(), (short) 0x03);

        putMapping(CalibratorGasSelectAttribute.M_GPTNO, (short) 0x04);
        putMapping(CalibratorGasSelectAttribute.M_GPTNO_O3, (short) 0x05);

        putMapping(AttributeClass.O3.getName(), (short) 0x66);

        putMapping(CalibratorGasSelectAttribute.M_GPT, (short) 0x6D);
    }

    private static void putMapping(String pollutant, short code) {
        STRING_TO_SHORT.put(pollutant, code);
        SHORT_TO_STRING.put(code, pollutant);
    }

    // 字符串到short的转换函数
    public static final Function<String, Short> TO_SHORT = pollutant -> STRING_TO_SHORT.getOrDefault(pollutant, null);

    // short到字符串的转换函数
    public static final Function<Short, String> TO_STRING = code -> SHORT_TO_STRING.getOrDefault(code, null);
}
