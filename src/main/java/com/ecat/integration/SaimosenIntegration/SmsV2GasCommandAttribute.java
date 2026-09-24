package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.StringCommandAttribute;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SMS8000 V2 气态串口校准命令。协议与先河 XH*2000BV2 的 ASCII 命令一致：
 * 命令模板、成功/失败标志，跨度浓度从 {@code CALIBRATION_CONCENTRATION} 拼入，固定 3 位。
 */
public class SmsV2GasCommandAttribute extends StringCommandAttribute {

    public enum CommandType {
        ZERO_START, ZERO_END, ZERO_CONFIRM, SPAN_START, SPAN_END, SPAN_CONFIRM
    }

    public static class CommandConfig {
        public String cmdTemplate;
        public String successFlag;
        public String failFlag;
        public CommandType type;

        public CommandConfig(String cmdTemplate, String successFlag, String failFlag, CommandType type) {
            this.cmdTemplate = cmdTemplate;
            this.successFlag = successFlag;
            this.failFlag = failFlag;
            this.type = type;
        }
    }

    private final Map<String, CommandConfig> commandConfigMap = new HashMap<>();
    private final SerialSource serialSource;
    private final ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy;

    public SmsV2GasCommandAttribute(String attributeID, AttributeClass attrClass, SerialSource serialSource) {
        super(attributeID, attrClass);
        this.serialSource = serialSource;
        this.responseHandlerStrategy = new ByteResponseHandlerStrategy<>(
                serialSource,
                this::processResponse,
                this::checkByteResponse,
                this::handleException
        );
    }

    public void registerCommand(String type, CommandConfig config) {
        commandConfigMap.put(type, config);
        setCommands(new ArrayList<>(commandConfigMap.keySet()));
    }

    @Override
    protected CompletableFuture<Boolean> sendCommandImpl(String type) {
        CommandConfig config = commandConfigMap.get(type);
        if (config == null) {
            log.error("未注册的命令类型: " + type);
            return CompletableFuture.completedFuture(false);
        }
        final String cmdToSend;
        switch (config.type) {
            case SPAN_START:
            case SPAN_END:
            case SPAN_CONFIRM:
                AttributeBase<?> attr = getDevice() != null ? getDevice().getAttrs().get("CALIBRATION_CONCENTRATION") : null;
                if (attr instanceof NumericAttribute) {
                    AttrState attrState = attr.getState();
                    Object raw = attrState != null ? attrState.getValue() : null;
                    if (!(raw instanceof Number)) {
                        log.error("SPAN 命令需要 CALIBRATION_CONCENTRATION 但值为 null，" +
                            "请先通过 LogicDevice span_concentration 属性设置浓度");
                        return CompletableFuture.completedFuture(false);
                    }
                    double concentration = ((Number) raw).doubleValue();
                    cmdToSend = config.cmdTemplate.replace("{value}", formatSpanConcentration(concentration));
                } else {
                    log.error("未找到或类型错误的CALIBRATION_CONCENTRATION属性");
                    return CompletableFuture.completedFuture(false);
                }
                break;
            default:
                cmdToSend = config.cmdTemplate;
        }
        final byte[] typeBytes = type.getBytes();
        return SerialTransactionStrategy.executeWithLambda(serialSource, source -> serialSource.asyncSendData(cmdToSend.getBytes())
                .thenCompose(v -> responseHandlerStrategy.handleResponse(new ByteResponseHandlingContext<>(typeBytes))))
                .thenApply(result -> result != null && result);
    }

    /**
     * 跨度浓度固定 3 位，不足左补 0。负值按 0。大于 999 时宽度自然扩展。
     */
    static String formatSpanConcentration(double value) {
        long rounded = Math.round(value);
        if (rounded < 0) {
            rounded = 0;
        }
        return String.format("%03d", rounded);
    }

    private Boolean processResponse(ByteResponseHandlingContext<byte[]> context) {
        String result = context.getReceiveBuffer().toString();
        String cmdType = decodeCommandType(context.getNewValue());
        CommandConfig config = commandConfigMap.get(cmdType);
        if (config == null) {
            throw new IllegalStateException("Unregistered command type: " + cmdType);
        }
        log.info("命令{}收到响应{}", cmdType, result);
        // 与先河 XH*2000BV2 相同：仪器未在校准模式时结束命令会回失败标志，这里收到响应即视为下发成功。
        return true;
    }

    protected byte[] checkByteResponse(byte[] buffer) {
        if (buffer != null && buffer.length > 0 && buffer[buffer.length - 1] == '$') {
            return buffer;
        }
        return null;
    }

    protected Boolean handleException(Throwable ex) {
        log.error("Response handling error: " + ex.getMessage());
        return false;
    }

    static String decodeCommandType(byte[] raw) {
        return raw == null ? null : new String(raw);
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        return value;
    }
}
