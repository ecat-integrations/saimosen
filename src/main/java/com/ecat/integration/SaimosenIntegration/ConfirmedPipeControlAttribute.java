package com.ecat.integration.SaimosenIntegration;

import java.util.concurrent.CompletableFuture;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusUnsignedShortAttribute;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;

/**
 * 带"写后同锁回读确认"的管路控制属性（SMS8240 {@code 0x0008} / {@code 0x0009}）。
 *
 * <p>为什么必须回读：板内是 2 秒缓存，写完若不等回读就返回，本轮轮询稍后可能先发布
 * 旧缓冲值，现场会看到"点了一下又跳回去"，误以为命令没生效。写与回读在同一 Modbus
 * 源锁内完成（{@link ModbusTransactionStrategy#executeWithLambda}），读写不会互相插帧。
 *
 * <p>写入值只允许 {@code 0}（贯通）/ {@code 1}（关闭）：其他值在发帧前直接拒绝，
 * 不取低位掩盖错误（协议异常码 03 的本地前置校验）。
 *
 * @author coffee
 */
public class ConfirmedPipeControlAttribute extends ModbusUnsignedShortAttribute {

    /** 管路贯通。 */
    public static final int OPEN = 0;
    /** 管路关闭。 */
    public static final int CLOSE = 1;

    private final int pipeRegisterAddress;

    /**
     * @param attributeID       属性 ID（如 {@code pm10_pipe_state}）
     * @param modbusSource      Modbus 源
     * @param registerAddress   管路控制寄存器（{@code 0x0008} / {@code 0x0009}）
     */
    public ConfirmedPipeControlAttribute(String attributeID, ModbusSource modbusSource, int registerAddress) {
        super(attributeID, AttributeClass.CONTROL,
                NoConversionUnit.of(""), NoConversionUnit.of(""), 0,
                false, true, modbusSource, registerAddress);
        this.pipeRegisterAddress = registerAddress;
    }

    /**
     * 供设备启动回正使用（与用户侧写入同一 final 写入口，走确认与记账语义）。
     */
    public CompletableFuture<Boolean> applyState(int state) {
        return setValue(state);
    }

    /** 当前已提交的寄存器值；无值返回 {@code -1}（不伪造"贯通"）。 */
    public int currentState() {
        Object value = getState() == null ? null : getState().getValue();
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    @Override
    protected CompletableFuture<Boolean> setValueImpl(Integer newValue) {
        if (newValue == null || (newValue != OPEN && newValue != CLOSE)) {
            throw new IllegalArgumentException("管路控制只允许 0(贯通)/1(关闭)，收到: " + newValue);
        }
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source ->
            source.writeRegister(pipeRegisterAddress, newValue)
                .thenCompose(writeResponse -> {
                    if (writeResponse == null || writeResponse.isException()) {
                        throw new RuntimeException("管路命令下发失败: "
                                + (writeResponse != null ? writeResponse.getExceptionMessage() : "无响应(设备离线/传输失败)")
                                + ", register=" + String.format("0x%04X", pipeRegisterAddress));
                    }
                    // 同锁回读：确认设备侧确实接受了本次写入
                    return source.readHoldingRegisters(pipeRegisterAddress, 1);
                })
                .thenCompose(readResponse -> {
                    if (readResponse == null || readResponse.isException()
                            || readResponse.getShortData() == null
                            || readResponse.getShortData().length < 1) {
                        throw new RuntimeException("管路回读失败: "
                                + (readResponse != null ? readResponse.getExceptionMessage() : "无响应(设备离线/传输失败)")
                                + ", register=" + String.format("0x%04X", pipeRegisterAddress));
                    }
                    int actual = readResponse.getShortData()[0] & 0xFFFF;
                    if (actual != newValue) {
                        throw new RuntimeException("管路回读不一致: 期望=" + newValue + ", 实际=" + actual
                                + ", register=" + String.format("0x%04X", pipeRegisterAddress));
                    }
                    return CompletableFuture.completedFuture(Boolean.TRUE);
                }));
    }
}
