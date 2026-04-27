package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SerialSource;

import java.util.concurrent.CompletableFuture;

/**
 * 钢瓶气数值属性
 */
public class SMS8600V2CylinderGasNumericAttribute extends NumericAttribute {
    // 通道ID 如果不存在则无法设置钢瓶气浓度
    private String channelId;
    // 串口
    private SerialSource serialSource;
    // 响应处理策略
    private ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy;
    public SMS8600V2CylinderGasNumericAttribute(String attributeID, AttributeClass attrClass, UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision, boolean unitChangeable, boolean valueChangeable, SerialSource serialSource, ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy) {
        super(attributeID, attrClass, nativeUnit, displayUnit, displayPrecision, unitChangeable, valueChangeable);
        this.serialSource = serialSource;
        this.responseHandlerStrategy = responseHandlerStrategy;
    }

    /**
     * 公开方法，提供设置通道ID的方法， 如果不存在通道ID则无法设置钢瓶气浓度
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }
    /**
     * 公开方法，提供获取通道ID的方法
     */
    public String getChannelId() {
        return channelId;
    }

    @Override
    public CompletableFuture<Boolean> setValue(Double newValue) {
        if (!valueChangeable) {
            return  CompletableFuture.completedFuture(false);
        }
        if(newValue == null){
            return CompletableFuture.completedFuture(false);
        }
        if(channelId == null){
            log.warn("SMS8600V2CylinderGasNumericAttribute - channelId is null, cannot set value");
            return CompletableFuture.completedFuture(false);
        }
        // 构建命令
        String cmd = "calppm," + channelId + "," + newValue+"$";
        return serialSource.asyncSendData(cmd.getBytes())
                .thenCompose(v -> {
                    // responseHandlerStrategy.handleResponse(new ResponseHandlingContext<>(cmd))
                    // 创建 ByteResponseHandlingContext，使用命令作为上下文值
                    ByteResponseHandlingContext<byte[]> context = new ByteResponseHandlingContext<>(cmd.getBytes());
                    log.info("SMS8600V2CylinderGasNumericAttribute - send calppm cmd: {}", cmd);
                    return responseHandlerStrategy.handleResponse(context);
                });
    }
}
