package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;

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

    public SMS8600V2DeviceStringSelectAttribute(String attributeID, AttributeClass attrClass, boolean valueChangeable, List<String> options, ChannelNumber channelNumber, SerialSource serialSource) {
        super(attributeID, attrClass, valueChangeable, options);
        this.channelNumber = channelNumber;
        this.serialSource = serialSource;
        this.responseHandlerStrategy = new ByteResponseHandlerStrategy<>(
                serialSource,
                this::processResponse,
                this::checkByteResponse,
                this::handleException
        );
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
        return SerialTransactionStrategy.executeWithLambda(serialSource, source -> {
            return serialSource.asyncSendData(cmd.getBytes())
                    .thenCompose(v -> {
                        ByteResponseHandlingContext<byte[]> context = new ByteResponseHandlingContext<>(cmd.getBytes());
                        //log.info("PM3006SDevice {} - Handling response context: {}", getId(), context);

                        return responseHandlerStrategy.handleResponse(context);
                        //responseHandlerStrategy.handleResponse(new ResponseHandlingContext<>(type))
                    });
        }).thenApply(result -> result != null && result);
    }

    /**
     * 响应处理方法，判断返回内容是否包含成功/失败标志。
     * @param context 响应上下文
     * @return 是否成功
     */
    private Boolean processResponse(ByteResponseHandlingContext<byte[]> context) {
        String result = context.getReceiveBuffer().toString();
        log.info("命令{}收到响应{}", context.getNewValue(), result);
        if((new String(context.getNewValue())).startsWith("calcha,")){
            if(result.equals("calchaok$")){
                return true;
            }
        }
        return false; // 只要有响应就认为成功
    }

    protected byte[] checkByteResponse(byte[] buffer) {
        // $ 对应的ASCII字节是 0x24，判断是否以 $ 结束
        if (buffer == null || buffer.length == 0) {
            return new byte[0];
        }

        // 获取最后一个字节，判断是否是 $
        byte endChar = 0x24; // $ 符号
        if (buffer[buffer.length - 1] == endChar) {
            // 以 $ 结尾，返回完整数据
            return buffer;
        } else {
            // 不是以 $ 结尾，返回空数组表示不完整
            return null;
        }
    }

    protected Boolean handleException(Throwable ex) {
        log.error("Response handling error: " + ex.getMessage());
        return false;
    }

}
