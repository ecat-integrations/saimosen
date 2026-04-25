package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SerialSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SMS8600V2DeviceStringSelectAttribute class
 *
 * 处理SMS8600V2设备的字符串选择属性，支持从预定义的字符串列表中选择
 **/
public class SMS8600V2DeviceStringSelectAttribute extends StringSelectAttribute {

    // 通道号注册
    private ChannelNumber channelNumber;
    // 串口
    private SerialSource serialSource;
    // 响应处理策略
    private ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy;

    public SMS8600V2DeviceStringSelectAttribute(String attributeID, AttributeClass attrClass, boolean valueChangeable, List<String> options, ChannelNumber channelNumber, SerialSource serialSource, ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy) {
        super(attributeID, attrClass, valueChangeable, options);
        this.channelNumber = channelNumber;
        this.serialSource = serialSource;
        this.responseHandlerStrategy = responseHandlerStrategy;
    }
    // 添加个枚举 用于表示 可以设置的通道位  一共四位 分别为 1,2,3,4
    public enum ChannelNumber {
        CHANNEL_1("1"), CHANNEL_2("2"), CHANNEL_3("3"), CHANNEL_4("4");
        private final String channelId;
        ChannelNumber(String channelId) {
            this.channelId = channelId;
        }
        public String getChannelId() {
            return channelId;
        }
    }

    // 下拉选择触发
    @Override
    public CompletableFuture<Boolean> selectOptionImp(String option) {
        if (!valueChangeable) {
            return  CompletableFuture.completedFuture(false);
        }
        String cmd = "calcha," + channelNumber.channelId + "," + option+"$";
        return serialSource.asyncSendData(cmd.getBytes())
                .thenCompose(v -> {
                    // responseHandlerStrategy.handleResponse(new ResponseHandlingContext<>(cmd))
                    // 创建 ByteResponseHandlingContext，使用命令作为上下文值
                    ByteResponseHandlingContext<byte[]> context = new ByteResponseHandlingContext<>(cmd.getBytes());
                    log.info("SMS8600V2Device - send calcha cmd: {}", cmd);
                    return responseHandlerStrategy.handleResponse(context);
                });
    }

}
