package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.*;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 动态气体校准仪设备控制指令，支持灵活配置命令模板和返回判断规则。
 * 支持通过设备属性动态拼接命令参数。
 * 命令下发采用事务策略，确保原子性。
 */
public class SMS8600V2DeviceCommandAttribute extends StringCommandAttribute {

    /**
     * 命令配置类，包含命令模板、成功/失败标志、是否需要参数。
     */
    /**
     * 命令类型枚举，四类命令
     */
    public enum CommandType {
        ZERO_START, ZERO_END, SPAN_START, SPAN_END, GPT_START, GPT_END
    }

    /**
     * 命令配置类，包含命令模板、成功/失败标志、命令类型
     */
    public static class CommandConfig {
        public String cmdTemplate; // 如 "sspans {value}$"
        public String successFlag; // 如 "spansok$"
        public String failFlag;    // 如 "spansfa$"
        public CommandType type;   // 命令类型
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

    /**
     * 构造函数
     * @param attributeID 属性ID
     * @param attributeName 属性名称
     * @param attrClass 属性类型
     * @param serialSource 串口源
     */
    public SMS8600V2DeviceCommandAttribute(String attributeID, String attributeName, AttributeClass attrClass, SerialSource serialSource) {
        super(attributeID, attributeName, attrClass);
        this.serialSource = serialSource;
        // 初始化响应处理策略，处理命令响应和异常
        this.responseHandlerStrategy = new ByteResponseHandlerStrategy<>(
                serialSource,
                this::processResponse,
                this::checkByteResponse,
                this::handleException
        );
    }

    /**
     * 构造函数（支持i18n）
     * @param attributeID 属性ID
     * @param attrClass 属性类型
     * @param serialSource 串口源
     */
    public SMS8600V2DeviceCommandAttribute(String attributeID, AttributeClass attrClass, SerialSource serialSource) {
        this(attributeID, null, attrClass, serialSource);
    }

    /**
     * 注册命令类型及其模板和返回判断规则
     * @param type 命令类型标识
     * @param config 命令配置
     */
    public void registerCommand(String type, CommandConfig config) {
        commandConfigMap.put(type, config);
        setCommands(new ArrayList<>(commandConfigMap.keySet()));
    }

    /**
     * 发送命令，采用事务策略，确保命令下发和响应读取的原子性。
     * @param type 命令类型
     * @return 命令执行是否成功
     */
    @Override
    protected CompletableFuture<Boolean> sendCommandImpl(String type) {
        CommandConfig config = commandConfigMap.get(type);
        if (config == null) {
            log.error("未注册的命令类型: " + type);
            return CompletableFuture.completedFuture(false);
        }
        final String cmdToSend;
        // 只有跨度相关命令需要参数
        switch (config.type) {
            case ZERO_START:
                AttributeBase<?> calibration_flow_config = getDevice().getAttrs().get("calibration_flow_config");
                if(!(calibration_flow_config instanceof NumericAttribute)){
                    log.error("零点校准执行异常,流量属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(calibration_flow_config.getState() == null || calibration_flow_config.getState().getValue() == null){
                        log.error("零点校准执行异常,未配置校准流量！");
                        return CompletableFuture.completedFuture(false);
                    }
                }
                // 成功
                double value = ((Number) calibration_flow_config.getState().getValue()).doubleValue();
                cmdToSend = config.cmdTemplate.replace("{flow}", String.format("%04d", (int) value));
                log.info("零点校准执行成功,校准流量:{}", value);
                break;
            case SPAN_START:
                AttributeBase<?> span_calibration_flow_config = getDevice().getAttrs().get("calibration_flow_config");
                if(!(span_calibration_flow_config instanceof NumericAttribute)){
                    log.error("跨度校准执行异常,流量属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(span_calibration_flow_config.getState() == null || span_calibration_flow_config.getState().getValue() == null){
                        log.error("跨度校准执行异常,未配置校准流量！");
                        return CompletableFuture.completedFuture(false);
                    }
                }
                AttributeBase<?> calibration_gas_config = getDevice().getAttrs().get("calibration_gas_config");
                if(!(calibration_gas_config instanceof StringSelectAttribute)){
                    log.error("跨度校准执行异常,气体属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(calibration_gas_config.getState() == null || calibration_gas_config.getState().getValue() == null){
                        log.error("跨度校准执行异常,未配置校准气体！");
                        return CompletableFuture.completedFuture(false);
                    }
                }
                AttributeBase<?> calibration_concentration_config = getDevice().getAttrs().get("calibration_concentration_config");
                if(!(calibration_concentration_config instanceof NumericAttribute)){
                    log.error("跨度校准执行异常,浓度属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(calibration_concentration_config.getState() == null || calibration_concentration_config.getState().getValue() == null){
                        log.error("跨度校准执行异常,未配置校准浓度！");
                        return CompletableFuture.completedFuture(false);
                    }
                }
                AttributeBase<?> calibration_concentration_unit_config = getDevice().getAttrs().get("calibration_concentration_unit_config");
                if(!(calibration_concentration_unit_config instanceof StringSelectAttribute)){
                    log.error("跨度校准执行异常,校准单位属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(calibration_concentration_unit_config.getState() == null || calibration_concentration_unit_config.getState().getValue() == null){
                        log.error("跨度校准执行异常,未配置校准单位！");
                        return CompletableFuture.completedFuture(false);
                    }
                }
                double flow_value = ((Number) span_calibration_flow_config.getState().getValue()).doubleValue();
                String gas = (String) calibration_gas_config.getState().getValue();
                double concentration_value = ((Number) calibration_concentration_config.getState().getValue()).doubleValue();
                String concentration_unit = (String) calibration_concentration_unit_config.getState().getValue();
                cmdToSend = config.cmdTemplate.replace("{flow}", String.format("%04d", (int) flow_value))
                        .replace("{gas}", gas)
                        .replace("{concentration}", String.format("%03d", (int) concentration_value))
                        .replace("{concentration_unit}", concentration_unit);
                log.info("跨度校准执行成功,校准流量:{}，校准气体:{}，校准浓度:{}，校准单位:{}", flow_value, gas, concentration_value, concentration_unit);
                break;
            case GPT_START:
                AttributeBase<?> gpt_calibration_flow_config = getDevice().getAttrs().get("calibration_flow_config");
                if(!(gpt_calibration_flow_config instanceof NumericAttribute)){
                    log.error("gpt校准执行异常,流量属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(gpt_calibration_flow_config.getState() == null || gpt_calibration_flow_config.getState().getValue() == null){
                        log.error("gpt校准执行异常,未配置校准流量！");
                        // 抛出
                        return CompletableFuture.completedFuture(false);
                    }
                }
                AttributeBase<?> calibration_gpt_no_concentration_config = getDevice().getAttrs().get("calibration_gpt_no_concentration_config");
                if(!(calibration_gpt_no_concentration_config instanceof NumericAttribute)){
                    log.error("gpt校准执行异常,no浓度属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(calibration_gpt_no_concentration_config.getState() == null || calibration_gpt_no_concentration_config.getState().getValue() == null){
                        log.error("gpt校准执行异常,no未配置校准浓度！");
                        return CompletableFuture.completedFuture(false);
                    }
                }
                AttributeBase<?> calibration_gpt_o3_concentration_config = getDevice().getAttrs().get("calibration_gpt_o3_concentration_config");
                if(!(calibration_gpt_o3_concentration_config instanceof NumericAttribute)){
                    log.error("gpt校准执行异常,o3浓度属性对象错误！");
                    return CompletableFuture.completedFuture(false);
                }else{
                    // 从不可变 state 读，getValue 已封装为 protected
                    if(calibration_gpt_o3_concentration_config.getState() == null || calibration_gpt_o3_concentration_config.getState().getValue() == null){
                        log.error("gpt校准执行异常,o3浓度未配置校准浓度！");
                        return CompletableFuture.completedFuture(false);
                    }
                }
                double gpt_flow_value = ((Number) gpt_calibration_flow_config.getState().getValue()).doubleValue();
                double gpt_no_concentration_value = ((Number) calibration_gpt_no_concentration_config.getState().getValue()).doubleValue();
                double gpt_o3_concentration_value = ((Number) calibration_gpt_o3_concentration_config.getState().getValue()).doubleValue();
                cmdToSend = config.cmdTemplate.replace("{flow}", String.format("%04d", (int) gpt_flow_value))
                        .replace("{no_concentration}", String.format("%03d", (int) gpt_no_concentration_value))
                        .replace("{o3_concentration}", String.format("%03d", (int) gpt_o3_concentration_value));
                log.info("gpt校准执行成功,校准流量:{}，校准no浓度:{}，校准o3浓度:{}", gpt_flow_value, gpt_no_concentration_value, gpt_o3_concentration_value);
                break;
            default:
                cmdToSend = config.cmdTemplate;
        }
        // 事务策略下发命令并处理响应  加锁隔离
        return SerialTransactionStrategy.executeWithLambda(serialSource, source -> {
            return serialSource.asyncSendData(cmdToSend)
                .thenCompose(v -> {
                    ByteResponseHandlingContext<byte[]> context = new ByteResponseHandlingContext<>(type.getBytes());
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
        if((new String(context.getNewValue())).equals("span_end")){
            if(result.equals("calsendok$")){
                return true;
            }
        } else if ((new String(context.getNewValue())).equals("zero_end")) {
            if(result.equals("calzendok$")){
                return true;
            }
        }else if ((new String(context.getNewValue())).equals("span_start")) {
            if(result.equals("calspanok$")){
                return true;
            }
        }else if ((new String(context.getNewValue())).equals("zero_start")) {
            if(result.equals("calzerook$")){
                return true;
            }
        }else if ((new String(context.getNewValue())).equals("gpt_start")) {
            if(result.equals("calgptok$")){
                return true;
            }
        }else if ((new String(context.getNewValue())).equals("gpt_end")) {
            if(result.equals("calgendok$")){
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

    protected String checkReadBuffer(String response) {
        if (response.endsWith("$")) {
            return response;
        }
        return null;
    }

    protected Boolean handleException(Throwable ex) {
        log.error("Response handling error: " + ex.getMessage());
        return false;
    }

    @Override
    public String getDisplayValue(UnitInfo toUnit) {
        return value;
    }
}
