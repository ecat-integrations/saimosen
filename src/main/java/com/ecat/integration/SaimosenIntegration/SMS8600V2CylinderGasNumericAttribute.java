package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;

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
    public SMS8600V2CylinderGasNumericAttribute(String attributeID, AttributeClass attrClass, UnitInfo nativeUnit, UnitInfo displayUnit, int displayPrecision, boolean unitChangeable, boolean valueChangeable, SerialSource serialSource) {
        super(attributeID, attrClass, nativeUnit, displayUnit, displayPrecision, unitChangeable, valueChangeable);
        this.serialSource = serialSource;
        this.responseHandlerStrategy = new ByteResponseHandlerStrategy<>(
                serialSource,
                this::processResponse,
                this::checkByteResponse,
                this::handleException
        );
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

    // IO 载荷钩子（22 号 setValue final 化）：IO = calppm 标定命令事务（发帧+calppmok$ 应答
    // 确认），确认成功后的本地收尾（updateValue+publicState）/失败记账（commandFailed）/
    // 值变更门禁由 final setValue 入口统一持有——修复此前自拼 CF 只做 IO 不做任何本地收尾、
    // 设备已改而平台属性值/总线事件纹丝不动的断链（D-22-7 ①）
    @Override
    protected CompletableFuture<Boolean> setValueImpl(Double newValue) {
        if(newValue == null){
            return CompletableFuture.completedFuture(false);
        }
        if(channelId == null){
            log.warn("SMS8600V2CylinderGasNumericAttribute - channelId is null, cannot set value");
            return CompletableFuture.completedFuture(false);
        }
        // 构建命令
        String cmd = "calppm," + channelId + "," + newValue+"$";
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
        if((new String(context.getNewValue())).startsWith("calppm,")){
            if(result.equals("calppmok$")){
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
