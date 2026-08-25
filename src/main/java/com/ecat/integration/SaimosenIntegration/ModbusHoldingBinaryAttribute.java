package com.ecat.integration.SaimosenIntegration;

import java.util.concurrent.CompletableFuture;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.BinaryAttribute;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * 保持寄存器上的开关量（功能码 0x06 写 U16：0=关，1=开）。
 * {@link com.ecat.integration.ModbusIntegration.Attribute.ModbusBinaryAttribute} 写的是线圈，SMS8910 不适用。
 */
public class ModbusHoldingBinaryAttribute extends BinaryAttribute {

    private final ModbusSource modbusSource;
    private final int registerAddress;

    public ModbusHoldingBinaryAttribute(String attributeID, String displayName, AttributeClass attrClass,
            boolean valueChangeable, ModbusSource modbusSource, int registerAddress) {
        super(attributeID, displayName, attrClass, valueChangeable);
        this.modbusSource = modbusSource;
        this.registerAddress = registerAddress;
    }

    @Override
    protected CompletableFuture<Boolean> asyncTurnOnImpl() {
        return writeHoldingRegister(1);
    }

    @Override
    protected CompletableFuture<Boolean> asyncTurnOffImpl() {
        return writeHoldingRegister(0);
    }

    private CompletableFuture<Boolean> writeHoldingRegister(int value) {
        if (!valueChangeable) {
            return CompletableFuture.completedFuture(false);
        }
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source ->
                source.writeRegister(registerAddress, value)
                        .thenCompose(response -> {
                            if (response == null || response.isException()) {
                                throw new RuntimeException("命令下发失败: " + response.getExceptionMessage());
                            }
                            return CompletableFuture.completedFuture(true);
                        }));
    }
}
