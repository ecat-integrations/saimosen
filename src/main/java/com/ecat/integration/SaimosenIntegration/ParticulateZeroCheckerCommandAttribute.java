package com.ecat.integration.SaimosenIntegration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.StringCommandAttribute;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * 适用于8220设备的控制命令属性
 * 
 * @version V1.0
 * @author coffee
 */
public class ParticulateZeroCheckerCommandAttribute extends StringCommandAttribute{
    private final ModbusSource modbusSource;
    private final Map<String, Short> optionToBitMap;  // 选项到寄存器地址的映射

    // 寄存器地址定义, 默认值为0x0001和0x0002
    private short REG_TURN_ON = 0x0001;
    private short REG_TURN_OFF = 0x0002;

    // 运行状态
    private volatile boolean isRunning = false;

    public ParticulateZeroCheckerCommandAttribute(String attributeID, AttributeClass attrClass, ModbusSource modbusSource,
        short turn_on_addr, short turn_off_addr) {
        super(attributeID, attrClass);
        this.modbusSource = modbusSource; 
        // 设置寄存器地址
        this.REG_TURN_ON = turn_on_addr;
        this.REG_TURN_OFF = turn_off_addr;
        
        // 初始化选项到bit位的映射（根据2.3设备Controls定义）
        optionToBitMap = new LinkedHashMap<>();
        optionToBitMap.put("开启", REG_TURN_ON);
        optionToBitMap.put("关闭", REG_TURN_OFF);

        setCommands(new ArrayList<>(optionToBitMap.keySet()));
    }

    @Override
    protected CompletableFuture<Boolean> sendCommandImpl(String cmd) {
        // 校验选项是否合法
        if (!commands.contains(cmd)) {
            log.error("无效的命令选项: " + cmd);
            return CompletableFuture.completedFuture(false);
        }

        // 获取选项对应的位置
        Short bit = optionToBitMap.get(cmd);
        if (bit == null) {
            log.error("未找到选项对应的index: " + cmd);
            return CompletableFuture.completedFuture(false);
        }

        // 构造要写入的寄存器值（将对应bit置1，其他位保持0）
        short registerAddr = bit.shortValue();

        // 调用Modbus写寄存器逻辑
        return handleStateChange(registerAddr);
    }

    private CompletableFuture<Boolean> handleStateChange(short registerAddr) {
        // 检查是否正在运行
        if (isRunning) {
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("设备正在执行开关操作，请等待完成"));
            return failedFuture;
        }
        
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {

            return source.writeRegister(registerAddr, (short) 0)
                .thenApply(response -> {
                    if (response == null || response.isException()) {
                        throw new RuntimeException("写入寄存器失败，地址: " + registerAddr + "，值: " +  "0");
                    }
                    isRunning = false;
                    return true;
                })
                
                // 异常处理
                .exceptionally(ex -> {
                    isRunning = false; // 确保在异常情况下也能恢复运行状态
                    throw new RuntimeException("开关切换过程中发生错误: " + ex.getMessage(), ex);
                });
            });
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        return value;
    }

    
}
