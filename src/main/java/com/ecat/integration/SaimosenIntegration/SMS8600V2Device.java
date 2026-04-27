
package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.*;
import com.ecat.core.State.Unit.*;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;
import com.ecat.integration.SerialIntegration.SerialTransactionStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SMS8600V2Device
 * SMS8600V2Device 动态气体校准仪
 * 
 */
public class SMS8600V2Device extends SerialDeviceBase {
    public Double molecularWeight = 28.0; //分子质量

    @Override
    public String getTypeName() {
        return "sms8600v2device";
    }
    // 读取实时浓度值 O3
    private static final String O3_REAL_DATA_CMD = "calochr$";
    // 读取分钟浓度值 O3
    private static final String O3_MINUTE_DATA_CMD = "calocha$";
    // 仪器状态调取
    private static final String STATUS_CMD = "calotwc$";
    // 气体设置读取
    private static final String GAS_SETTING_CMD = "calppm,?$";

    /**
     *  校准气体映射
     */
    private static final Map<String, String> gasMapper = new HashMap<>();
    static {
        gasMapper.put("S", "S");
        gasMapper.put("N", "N");
        gasMapper.put("O", "O");
        gasMapper.put("C", "C");
    }
    /**
     *  校准浓度单位
     */
    private static final Map<String, String> concentrationUnitMapper = new HashMap<>();
    static {
        concentrationUnitMapper.put("PPB","PPB");
        concentrationUnitMapper.put("PPM","PPM");
    }
    /**
     *  气体编号映射
     */
    private static final Map<String, String> gasNumberMapper = new HashMap<>();
    static {
        gasNumberMapper.put("0", "0");
        gasNumberMapper.put("1", "SO2");
        gasNumberMapper.put("2", "NO");
        gasNumberMapper.put("3", "CO");
        gasNumberMapper.put("4", "CH4");
        gasNumberMapper.put("5", "O3");
        gasNumberMapper.put("6", "GAS1");
        gasNumberMapper.put("7", "GSS2");
        gasNumberMapper.put("8", "GAS3");
        gasNumberMapper.put("9", "GAS4");
    }
    // 气体ID
    private static final String SO2CHANNELID = "1";
    private static final String NOCHANNELID = "2";
    private static final String COCHANNELID = "3";

    private ByteResponseHandlerStrategy<byte[]>  responseHandlerStrategy;

    public SMS8600V2Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public void init() {
        super.init();
        this.responseHandlerStrategy = new ByteResponseHandlerStrategy<>(
                serialSource,
                this::processResponse,
                this::checkByteResponse,
                this::handleException
        );
        createAttributes();
    }

    @Override
    public void start() {
        this.scheduledFuture = getScheduledExecutor().scheduleWithFixedDelay(() -> {
            SerialTransactionStrategy.executeWithLambda(serialSource, source -> {
                // 命令之间增加300ms延迟以适应设备性能
                return getRealData()
                    .thenCompose(v -> delay(300, TimeUnit.MILLISECONDS).thenCompose(w ->
                            getStatusData().thenCompose(x ->
                                    delay(300, TimeUnit.MILLISECONDS).thenCompose(z ->
                                            getGasSetting().thenCompose(y ->
                                                    delay(300, TimeUnit.MILLISECONDS).thenCompose(p ->
                                                            getMinuteData()))))
                    ));
            }).thenAccept(result -> {
                if (!Boolean.TRUE.equals(result)) {
                    log.warn("XHCAL2000BDevice {} - Failed to read device data", getId());
                }
            }).exceptionally(ex -> {
                log.error("XHCAL2000BDevice {} - Error reading device data", getId(), ex);
                return null;
            });
        }, 0, 5, TimeUnit.SECONDS);
    }


    /**
     * 创建异步延迟Future，用于在命令之间添加延迟以适应设备性能
     * @param delay 延迟时间
     * @param unit 时间单位
     * @return CompletableFuture<Void>
     */
    private CompletableFuture<Void> delay(long delay, TimeUnit unit) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        getScheduledExecutor().schedule(() -> future.complete(null), delay, unit);
        return future;
    }

    private void createAttributes() {
        // 实时臭氧浓度
        setAttribute(new AQAttribute("o3", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 2, true, false, molecularWeight));
        // 分钟臭氧浓度
        setAttribute(new AQAttribute("o3_minute", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 2, true, false, molecularWeight));
        // 测量电压
        setAttribute(new NumericAttribute("pmt_v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        // 参比电压
        setAttribute(new NumericAttribute("canbi_v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        // 预留1
        setAttribute(new NumericAttribute("yuliu1", AttributeClass.VALUE, null, null, 3, true, false));
        // 电源组件
        setAttribute(new NumericAttribute("power_component", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        // 光度计样气压力
        setAttribute(new NumericAttribute("photometer_press", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        // 光度计氧气流量
        setAttribute(new NumericAttribute("photometer_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        // 光度计样气温度
        setAttribute(new NumericAttribute("photometer_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        // 机箱温度
        setAttribute(new NumericAttribute("case_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        // 光度计灯温度
        setAttribute(new NumericAttribute("photometer_lamp_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        // 预留2
        setAttribute(new NumericAttribute("yuliu2", AttributeClass.VALUE, null, null, 3, true, false));
        // 臭氧发生器温度 o3_generator_temp
        setAttribute(new NumericAttribute("o3_generator_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        // 零气压力
        setAttribute(new NumericAttribute("zero_press", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        // 标气压力
        setAttribute(new NumericAttribute("span_press", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        // 调节阀压力  valve_press
        setAttribute(new NumericAttribute("valve_press", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        // 零气流量
        setAttribute(new NumericAttribute("zero_flow", AttributeClass.FLOW, LiterFlowUnit.L_PER_MINUTE, LiterFlowUnit.L_PER_MINUTE, 3, true, false));
        // 标气流量
        setAttribute(new NumericAttribute("span_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        // O3驱动电压  o3_drive_v
        setAttribute(new NumericAttribute("o3_drive_v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        // O3产生电压  o3_output_v
        setAttribute(new NumericAttribute("o3_output_v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        // 臭氧发生器流量 o3_generator_flow
        setAttribute(new NumericAttribute("o3_generator_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        // 斜率 slope
        setAttribute(new NumericAttribute("slope", AttributeClass.VALUE, null, null, 5, true, false));
        // 截距 intercept
        setAttribute(new NumericAttribute("intercept", AttributeClass.VALUE, null, null, 5, true, false));
        // 报警代码
        setAttribute(new TextAttribute("alarm_code", AttributeClass.STATUS, null, null, false));
        // 工作状态
//        setAttribute(new TextAttribute("work_status", AttributeClass.STATUS, null, null, false));

        // 虚拟属性  co标气流量
        setAttribute(new NumericAttribute("co_span_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        // 虚拟属性  so2标气流量
        setAttribute(new NumericAttribute("so2_span_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        // 虚拟属性  no2标气流量
        setAttribute(new NumericAttribute("no2_span_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));

        // SO2钢瓶气浓度  需要改造成-串口控制型属性，且可以配置信息
        setAttribute(new SMS8600V2CylinderGasNumericAttribute("gas_so2_cylinder_gas_conc", AttributeClass.OTHER_GAS_CONCENTRATION,
                AirVolumeUnit.PPM, AirVolumeUnit.PPM, 3,
                true, true, serialSource,
                responseHandlerStrategy));
        // CO钢瓶气浓度
        setAttribute(new SMS8600V2CylinderGasNumericAttribute("gas_co_cylinder_gas_conc", AttributeClass.OTHER_GAS_CONCENTRATION,
                AirVolumeUnit.PPM, AirVolumeUnit.PPM, 3,
                true, true, serialSource,
                responseHandlerStrategy));
        // NO2钢瓶气浓度
        setAttribute(new SMS8600V2CylinderGasNumericAttribute("gas_no_cylinder_gas_conc", AttributeClass.OTHER_GAS_CONCENTRATION,
                AirVolumeUnit.PPM, AirVolumeUnit.PPM, 3,
                true, true, serialSource,
                responseHandlerStrategy));
        // 校准气体配置
        List<String> gasNumberValveOptions = new ArrayList<>(gasNumberMapper.keySet());
        // 通道1气体
        setAttribute(new SMS8600V2DeviceStringSelectAttribute("channel_1_gas", AttributeClass.VALUE, true,
                gasNumberValveOptions,
                SMS8600V2DeviceStringSelectAttribute.ChannelNumber.CHANNEL_1, serialSource,
                responseHandlerStrategy));
        // 通道2气体
        setAttribute(new SMS8600V2DeviceStringSelectAttribute("channel_2_gas", AttributeClass.VALUE, true,
                gasNumberValveOptions,
                SMS8600V2DeviceStringSelectAttribute.ChannelNumber.CHANNEL_2, serialSource,
                responseHandlerStrategy));
        // 通道3气体
        setAttribute(new SMS8600V2DeviceStringSelectAttribute("channel_3_gas", AttributeClass.VALUE, true,
                gasNumberValveOptions,
                SMS8600V2DeviceStringSelectAttribute.ChannelNumber.CHANNEL_3, serialSource,
                responseHandlerStrategy));
        // 通道4气体
        setAttribute(new SMS8600V2DeviceStringSelectAttribute("channel_4_gas", AttributeClass.VALUE, true,
                gasNumberValveOptions,
                SMS8600V2DeviceStringSelectAttribute.ChannelNumber.CHANNEL_4, serialSource,
                responseHandlerStrategy));
        // 校准流量配置
        setAttribute(new NumericAttribute("calibration_flow_config", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 0, true, true));
        // 校准气体配置
        List<String> gasValveOptions = new ArrayList<>(gasMapper.values());
        setAttribute(new StringSelectAttribute("calibration_gas_config", AttributeClass.STATUS, true, gasValveOptions));
        // 校准浓度配置
        setAttribute(new NumericAttribute("calibration_concentration_config", AttributeClass.VALUE, null, null, 0, true, true));
        // gpt校准no浓度配置
        setAttribute(new NumericAttribute("calibration_gpt_no_concentration_config", AttributeClass.VALUE, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 0, true, true));
        // gpt校准o3浓度配置
        setAttribute(new NumericAttribute("calibration_gpt_o3_concentration_config", AttributeClass.VALUE, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 0, true, true));
        // 校准浓度单位配置
        List<String> concentrationUnitOptions = new ArrayList<>(concentrationUnitMapper.values());
        setAttribute(new StringSelectAttribute("calibration_concentration_unit_config", AttributeClass.STATUS, true, concentrationUnitOptions));
        // 校准指令
        SMS8600V2DeviceCommandAttribute commandAttr = new SMS8600V2DeviceCommandAttribute("calibration_cmd", AttributeClass.DISPATCH_COMMAND, serialSource);
        commandAttr.registerCommand("zero_start", new SMS8600V2DeviceCommandAttribute.CommandConfig(
                "calzero,{flow}$", "calzendok$", "calzendfa$", SMS8600V2DeviceCommandAttribute.CommandType.ZERO_START));
        commandAttr.registerCommand("zero_end", new SMS8600V2DeviceCommandAttribute.CommandConfig(
                "calzend$", "calzendok$", "calzendfa$", SMS8600V2DeviceCommandAttribute.CommandType.ZERO_END));
        commandAttr.registerCommand("span_start", new SMS8600V2DeviceCommandAttribute.CommandConfig(
                "calspan,{gas},{flow},{concentration},{concentration_unit}$", "calspanok$", "calspanfa$", SMS8600V2DeviceCommandAttribute.CommandType.SPAN_START));
        commandAttr.registerCommand("span_end", new SMS8600V2DeviceCommandAttribute.CommandConfig(
                "calsend$", "calsendok$", "calsendfa$", SMS8600V2DeviceCommandAttribute.CommandType.SPAN_END));
        commandAttr.registerCommand("gpt_start", new SMS8600V2DeviceCommandAttribute.CommandConfig(
                "calgpt,{no_concentration},{o3_concentration},{flow}$", "calgptok", "calgptfa$", SMS8600V2DeviceCommandAttribute.CommandType.GPT_START));
        commandAttr.registerCommand("gpt_end", new SMS8600V2DeviceCommandAttribute.CommandConfig(
                "calgend$", "calgendok$", "calgendfa$", SMS8600V2DeviceCommandAttribute.CommandType.GPT_END));
        setAttribute(commandAttr);
        // 工作状态 解析状态协议23位（2是跨度 1是零点 0正常） 22位（只要不是0说明有报警）
        List<String> options = AttributeStatus.getNames();
        setAttribute(new StringSelectAttribute("work_status",AttributeClass.MODE,true,options));
        getAttrs().get("work_status").setDisplayValue(AttributeStatus.NORMAL.getName());

    }

    private CompletableFuture<Boolean> getRealData() {
        log.debug("SMS8600V2Device {} - Sending command: {}", getId(), O3_REAL_DATA_CMD);
        return sendCommand(O3_REAL_DATA_CMD.getBytes());
    }

    private CompletableFuture<Boolean> getMinuteData() {
        log.debug("SMS8600V2Device {} - Sending command: {}", getId(), O3_MINUTE_DATA_CMD);
        return sendCommand(O3_MINUTE_DATA_CMD.getBytes());
    }


    private CompletableFuture<Boolean> getStatusData() {
        log.debug("SMS8600V2Device {} - Sending command: {}", getId(), STATUS_CMD);
        return sendCommand(STATUS_CMD.getBytes());
    }
    private CompletableFuture<Boolean> getGasSetting() {
        log.debug("SMS8600V2Device {} - Sending command: {}", getId(), GAS_SETTING_CMD);
        return sendCommand(GAS_SETTING_CMD.getBytes());
    }


    private CompletableFuture<Boolean> sendCommand(byte[] cmd) {
        return serialSource.asyncSendData(cmd)
               .thenCompose(v -> {
                   // responseHandlerStrategy.handleResponse(new ResponseHandlingContext<>(cmd))
                   // 创建 ByteResponseHandlingContext，使用命令作为上下文值
                   ByteResponseHandlingContext<byte[]> context = new ByteResponseHandlingContext<>(cmd);
                   log.info("XHCAL2000BDevice {} - Handling response context: {}, cmd: {}", getId(), context, cmd);
                   return responseHandlerStrategy.handleResponse(context);
               });
    }

    private Boolean processResponse(ByteResponseHandlingContext<byte[]> context) {
        String result = context.getReceiveBuffer().toString();
        if (result.endsWith("$")) {
            result = result.replace("$$", "$");
            result = result.replace("\r\n", "");
            String newValue = new String(context.getNewValue());
            if ((new String(context.getNewValue())).equals(O3_REAL_DATA_CMD)) {
                updateO3RealDataAttribute(result);
                return true;
            } else if ((new String(context.getNewValue())).equals(O3_MINUTE_DATA_CMD)) {
                updateO3MinuteDataAttribute(result);
                return true;
            } else if ((new String(context.getNewValue())).equals(STATUS_CMD)) {
                parseStatusResponse(result);
                return true;
            } else if ((new String(context.getNewValue())).equals(GAS_SETTING_CMD)) {
                parseGasSettingResponse(result);
                return true;
            } else if ((new String(context.getNewValue())).startsWith("calcha,")) {
                parseCalchaResponse(result, new String(context.getNewValue()));
                return true;
            } else if ((new String(context.getNewValue())).startsWith("calppm,")) {
                parseCalppmResponse(result, new String(context.getNewValue()));
                return true;
            }
        }
        log.warn("Unprocessed response: " + result);
        return false;
    }

    private void updateO3RealDataAttribute(String valueStr) {
        AQAttribute coAttr = (AQAttribute) getAttrs().get("o3");
        if (coAttr == null) {
            log.warn("o3 attribute not found");
            return;
        }
        try {
            // 使用基类方法确定最终状态
            AttributeStatus status = determineDataStatus(valueStr, "work_status", null);

            // 更新设备状态
            deviceStatus = mapToDeviceStatus(status);
            updateReadonlyStatusAttribute("work_status", status);
            // 解析数值
            String numericPart = valueStr;
            if (valueStr.startsWith("*") || valueStr.startsWith("#")) {
                numericPart = valueStr.substring(1);
            }
            numericPart = numericPart.replaceFirst("[*#]?O3=", "").replace("$", "");
            // 用正则提取=号后第一个数字串作为数值
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^([-+]?\\d*\\.?\\d+)").matcher(numericPart);
            double value = 0.0;
            if (matcher.find()) {
                value = Double.parseDouble(matcher.group(1));
            } else {
                log.error("CO value regex parse error: " + valueStr);
            }
            coAttr.updateValue(value, status);

            // 更新只读状态属性
            updateReadonlyStatusAttribute("work_status", status);

            publicAttrsState();
        } catch (Exception e) {
            log.error("O3 realdata value parse error: " + valueStr);
        }
    }
    private void updateO3MinuteDataAttribute(String valueStr) {
        AQAttribute coAttr = (AQAttribute) getAttrs().get("o3_minute");
        if (coAttr == null) {
            log.warn("o3 attribute not found");
            return;
        }
        try {
            // 使用基类方法确定最终状态
            AttributeStatus status = getDataStatus("work_status");


            // 解析数值
            String numericPart = valueStr;
            if (valueStr.startsWith("*") || valueStr.startsWith("#")) {
                numericPart = valueStr.substring(1);
            }
            numericPart = numericPart.replaceFirst("[*#]?O3=", "").replace("$", "");
            // 用正则提取=号后第一个数字串作为数值
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^([-+]?\\d*\\.?\\d+)").matcher(numericPart);
            double value = 0.0;
            if (matcher.find()) {
                value = Double.parseDouble(matcher.group(1));
            } else {
                log.error("CO value regex parse error: " + valueStr);
            }
            coAttr.updateValue(value, status);

            // 更新只读状态属性
            updateReadonlyStatusAttribute("work_status", status);

            publicAttrsState();
        } catch (Exception e) {
            log.error("O3 minute value parse error: " + valueStr);
        }
    }
    /**
     * 解析状态响应，兼容旧协议（10个字段）和新协议（12个字段）
     * 新协议在状态数据末端增加了斜率和截距两个字段
     *
     * @param result 状态响应字符串
     */
    private void parseStatusResponse(String result) {
        // 去除结尾$和前缀
        String statusStr = result.replace("$", "");
        String[] parts = statusStr.split(",");

        if (parts.length < 23) {
            log.warn("Status data has less than 23 fields: " + result);
            return;
        }

        // 使用 determineDataStatus 考虑手动状态优先级
        // 状态响应数据无前缀，传入空字符串；检查手动状态后，若无特殊设置则使用NORMAL
        AttributeStatus status = getDataStatus("work_status");
        String alarmCode = parts[21];
        String workStatus = parts[22];
        if(alarmCode.equals("0000")) { // 正常
            if(workStatus.equals("0") && status != AttributeStatus.MAINTENANCE){
                status = AttributeStatus.NORMAL;
                updateReadonlyStatusAttribute("work_status", status);
            } else if (workStatus.equals("1") && status != AttributeStatus.MAINTENANCE) {
                status = AttributeStatus.ZERO_CHECK;
                updateReadonlyStatusAttribute("work_status", status);
            } else if (workStatus.equals("2") && status != AttributeStatus.MAINTENANCE) {
                status = AttributeStatus.SPAN_CHECK;
                updateReadonlyStatusAttribute("work_status", status);
            }

        }else{
            status = AttributeStatus.ALARM;
            updateReadonlyStatusAttribute("work_status", status);
        }
        // 依次对应状态字段（前10个字段对所有协议版本都有效）
        updateAttribute("pmt_v", AttributeType.NUMERIC, parts[0], status);
        updateAttribute("canbi_v", AttributeType.NUMERIC, parts[1], status);
        updateAttribute("yuliu1", AttributeType.NUMERIC, parts[2], status);
        updateAttribute("power_component", AttributeType.NUMERIC, parts[3], status);
        updateAttribute("photometer_press", AttributeType.NUMERIC, parts[4], status);
        updateAttribute("photometer_flow", AttributeType.NUMERIC, parts[5], status);
        updateAttribute("photometer_temp", AttributeType.NUMERIC, parts[6], status);
        updateAttribute("case_temp", AttributeType.NUMERIC, parts[7], status);
        updateAttribute("photometer_lamp_temp", AttributeType.NUMERIC, parts[8], status);
        updateAttribute("yuliu2", AttributeType.NUMERIC, parts[9], status);
        updateAttribute("o3_generator_temp", AttributeType.NUMERIC, parts[10], status);
        updateAttribute("zero_press", AttributeType.NUMERIC, parts[11], status);
        updateAttribute("span_press", AttributeType.NUMERIC, parts[12], status);
        updateAttribute("valve_press", AttributeType.NUMERIC, parts[13], status);
        updateAttribute("zero_flow", AttributeType.NUMERIC, parts[14], status);
        String spanFlow = parts[15];
        updateAttribute("span_flow", AttributeType.NUMERIC, parts[15], status);
        updateAttribute("o3_drive_v", AttributeType.NUMERIC, parts[16], status);
        updateAttribute("o3_output_v", AttributeType.NUMERIC, parts[17], status);
        updateAttribute("o3_generator_flow", AttributeType.NUMERIC, parts[18], status);
        updateAttribute("slope", AttributeType.NUMERIC, parts[19], status);
        updateAttribute("intercept", AttributeType.NUMERIC, parts[20], status);
        updateAttribute("alarm_code", AttributeType.TEXT, alarmCode, status);
        try {
            String trimmedValue = spanFlow.trim();
            if ("-".equals(trimmedValue) || "---".equals(trimmedValue)) {
                log.debug("span_flow is null not update gas flow");
                return;  // 占位符数据，不更新属性，保持原状态
            }
            double flow_value = Double.parseDouble(spanFlow);
            if(flow_value > 0){ // 流量大于0  更新气体的流量
                StringSelectAttribute calibrationGasConfig = (StringSelectAttribute) getAttrs().get("calibration_gas_config");
                String gasName = calibrationGasConfig.getValue();
                if(gasName != null && gasMapper.get(gasName).equals("S")){
                    updateAttribute("so2_span_flow", AttributeType.NUMERIC, spanFlow, status);
                } else if (gasName != null && gasMapper.get(gasName).equals("N")) {
                    updateAttribute("no2_span_flow", AttributeType.NUMERIC, spanFlow, status);
                }else if (gasName != null && gasMapper.get(gasName).equals("C")) {
                    updateAttribute("co_span_flow", AttributeType.NUMERIC, spanFlow, status);
                }else{
                    updateAttribute("co_span_flow", AttributeType.NUMERIC, "0", status);
                    updateAttribute("so2_span_flow", AttributeType.NUMERIC, "0", status);
                    updateAttribute("no2_span_flow", AttributeType.NUMERIC, "0", status);
                }
            }else{
                updateAttribute("co_span_flow", AttributeType.NUMERIC, "0", status);
                updateAttribute("so2_span_flow", AttributeType.NUMERIC, "0", status);
                updateAttribute("no2_span_flow", AttributeType.NUMERIC, "0", status);
            }
        }catch (Exception e) {
            log.error("Work status parse error: " + workStatus);
        }

        StringSelectAttribute workStatusAttr = (StringSelectAttribute) getAttrs().get("work_status");
        workStatusAttr.updateValue(status.getName(), status);
        publicAttrsState();
    }

    /**
     * 解析气体设置响应
     * @param result 响应字符串
     */
    private void parseGasSettingResponse(String result){
        // 去除结尾$和前缀
        String statusStr = result.replace("$", "").replace("calppm,","");

        String[] parts = statusStr.split(",");

        if (parts.length < 8) {
            log.warn("Gas setting data has less than 3 fields: " + result);
            return;
        }
        AttributeStatus status = getDataStatus("work_status");
        // 依次对应状态字段（前10个字段对所有协议版本都有效）
        if(gasNumberMapper.get(parts[0]) != null){
            updateAttribute("channel_1_gas", AttributeType.SELECT, parts[0], status);
        }
        if (gasNumberMapper.get(parts[2]) != null){
            updateAttribute("channel_2_gas", AttributeType.SELECT, parts[2], status);
        }
        if (gasNumberMapper.get(parts[4]) != null){
            updateAttribute("channel_3_gas", AttributeType.SELECT, parts[4], status);
        }
        if (gasNumberMapper.get(parts[6]) != null){
            updateAttribute("channel_4_gas", AttributeType.SELECT, parts[6], status);
        }
        if(parts[1] != null && parts[3] != null && parts[5] != null && parts[7] != null){
            updateCylinderGasConc(parts, status);
        }
    }
    /**
     * 更新气体浓度
     * @param parts 气体通道数组
     */
    private void updateCylinderGasConc(String[] parts, AttributeStatus status){
        String channelOne = parts[0];
        String concOne = parts[1];
        String channelTwo = parts[2];
        String concTwo = parts[3];
        String channelThree = parts[4];
        String concThree = parts[5];
        String channelFour = parts[6];
        String concFour = parts[7];
        SMS8600V2CylinderGasNumericAttribute so2attr = (SMS8600V2CylinderGasNumericAttribute) getAttrs().get("gas_so2_cylinder_gas_conc");
        SMS8600V2CylinderGasNumericAttribute noattr = (SMS8600V2CylinderGasNumericAttribute) getAttrs().get("gas_no_cylinder_gas_conc");
        SMS8600V2CylinderGasNumericAttribute coattr = (SMS8600V2CylinderGasNumericAttribute) getAttrs().get("gas_co_cylinder_gas_conc");
        // 如果channelOne,channelTwo,concThree,concFour
        // ===================== 核心逻辑 =====================
        // 1. 处理 SO2 (通道ID=1)
        String so2Channel = null;
        if (SO2CHANNELID.equals(channelOne)) {
            so2Channel = "1";
            updateCylinderGasConcValue(so2attr, concOne, status);
        } else if (SO2CHANNELID.equals(channelTwo)) {
            so2Channel = "2";
            updateCylinderGasConcValue(so2attr, concTwo, status);
        } else if (SO2CHANNELID.equals(channelThree)) {
            so2Channel = "3";
            updateCylinderGasConcValue(so2attr, concThree, status);
        } else if (SO2CHANNELID.equals(channelFour)) {
            so2Channel = "4";
            updateCylinderGasConcValue(so2attr, concFour, status);
        }
        so2attr.setChannelId(so2Channel);

        // 2. 处理 NO (通道ID=2)
        String noChannel = null;
        if (NOCHANNELID.equals(channelOne)) {
            noChannel = "1";
            updateCylinderGasConcValue(noattr, concOne, status);
        } else if (NOCHANNELID.equals(channelTwo)) {
            noChannel = "2";
            updateCylinderGasConcValue(noattr, concTwo, status);
        } else if (NOCHANNELID.equals(channelThree)) {
            noChannel = "3";
            updateCylinderGasConcValue(noattr, concThree, status);
        } else if (NOCHANNELID.equals(channelFour)) {
            noChannel = "4";
            updateCylinderGasConcValue(noattr, concFour, status);
        }
        noattr.setChannelId(noChannel);
        // 3. 处理 CO (通道ID=3)
        String coChannel = null;
        if (COCHANNELID.equals(channelOne)) {
            coChannel = "1";
            updateCylinderGasConcValue(coattr, concOne, status);
        } else if (COCHANNELID.equals(channelTwo)) {
            coChannel = "2";
            updateCylinderGasConcValue(coattr, concTwo, status);
        } else if (COCHANNELID.equals(channelThree)) {
            coChannel = "3";
            updateCylinderGasConcValue(coattr, concThree, status);
        } else if (COCHANNELID.equals(channelFour)) {
            coChannel = "4";
            updateCylinderGasConcValue(coattr, concFour, status);
        }
        coattr.setChannelId(coChannel);
    }
    private void updateCylinderGasConcValue(SMS8600V2CylinderGasNumericAttribute attr,String conc, AttributeStatus status) {
        try {
            // 处理 "-" 或 "---" 表示无效值的情况
            String trimmedValue = conc.trim();
            if ("-".equals(trimmedValue) || "---".equals(trimmedValue)) {
                log.debug("Received placeholder value '" + trimmedValue + "' for attribute: " + attr.getAttributeID());
                return;  // 占位符数据，不更新属性，保持原状态
            }
            double value = Double.parseDouble(trimmedValue);
            attr.updateValue(value, status);
        } catch (NumberFormatException e) {
            log.error("Value is not numeric for NumericAttribute: " + attr.getAttributeID() + " = " + conc);
            // 解析失败时不设置状态，属性保持原状态
        }
    }


    /**
     * 解析校准气体响应
     * @param result 响应字符串
     */
    private void parseCalchaResponse(String result, String cmdStr){
        if(result.equals("calchaok$")){
            // 去除结尾$和前缀
            String cmd = cmdStr.replace("$", "").replace("calcha,", "");
            String[] cmds = cmd.split(",");
            if(cmds.length < 2){
                log.warn("Calibration data has less than 2 fields: " + cmd);
                return;
            }else{
                // 使用基类方法确定最终状态
                AttributeStatus status = getDataStatus("work_status");
                if(cmds[0].equals("1")){
                    updateAttribute("channel_1_gas", AttributeType.SELECT, cmds[1], status);
                }
                if (cmds[0].equals("2")){
                    updateAttribute("channel_2_gas", AttributeType.SELECT, cmds[1], status);
                }
                if (cmds[0].equals("3")){
                    updateAttribute("channel_3_gas", AttributeType.SELECT, cmds[1], status);
                }
                if (cmds[0].equals("4")){
                    updateAttribute("channel_4_gas", AttributeType.SELECT, cmds[1], status);
                }
            }
        }else{
            log.warn("parseCalchaResponse data error: " + result);
            return;
        }
    }

    /**
     * 解析校准气体响应
     * @param result 响应字符串
     */
    private void parseCalppmResponse(String result, String cmdStr){
        if(result.equals("calppmok$")) {
            // 去除结尾$和前缀
            String cmd = cmdStr.replace("$", "").replace("calppm,", "");
            String[] cmds = cmd.split(",");
            if(cmds.length < 2){
                log.warn("Calibration data has less than 2 fields: " + cmd);
                return;
            }else {
                // 使用基类方法确定最终状态
                AttributeStatus status = getDataStatus("work_status");
                // 获取属性对象
                SMS8600V2CylinderGasNumericAttribute so2attr = (SMS8600V2CylinderGasNumericAttribute) getAttrs().get("gas_so2_cylinder_gas_conc");
                SMS8600V2CylinderGasNumericAttribute noattr = (SMS8600V2CylinderGasNumericAttribute) getAttrs().get("gas_no_cylinder_gas_conc");
                SMS8600V2CylinderGasNumericAttribute coattr = (SMS8600V2CylinderGasNumericAttribute) getAttrs().get("gas_co_cylinder_gas_conc");
                if (so2attr.getChannelId().equals(cmds[0])){
                    updateCylinderGasConcValue(so2attr, cmds[1], status);
                }
                if (noattr.getChannelId().equals(cmds[0])){
                    updateCylinderGasConcValue(noattr, cmds[1], status);
                }
                if (coattr.getChannelId().equals(cmds[0])){
                    updateCylinderGasConcValue(coattr, cmds[1], status);
                }
                log.info("Update cylinder gas concentration: " + so2attr.getAttributeID() + " = " + so2attr.getValue());
            }
        }else{
            log.warn("parseCalppmResponse data error: " + result);
            return;
        }
    }
    private void updateAttribute(String attrId, AttributeType type, String valueStr, AttributeStatus status) {
        AttributeBase<?> attr = getAttrs().get(attrId);
        if (attr == null) {
            log.warn("Attribute not found: " + attrId);
            return;
        }
        if (type == AttributeType.NUMERIC && attr instanceof NumericAttribute) {
            try {
                // 处理 "-" 或 "---" 表示无效值的情况
                String trimmedValue = valueStr.trim();
                if ("-".equals(trimmedValue) || "---".equals(trimmedValue)) {
                    log.debug("Received placeholder value '" + trimmedValue + "' for attribute: " + attrId);
                    return;  // 占位符数据，不更新属性，保持原状态
                }
                double value = Double.parseDouble(valueStr);
                ((NumericAttribute) attr).updateValue(value, status);
            } catch (NumberFormatException e) {
                log.error("Value is not numeric for NumericAttribute: " + attrId + " = " + valueStr);
                // 解析失败时不设置状态，属性保持原状态
            }
        } else if (type == AttributeType.TEXT && attr instanceof TextAttribute) {
            try {
                ((TextAttribute) attr).updateValue(valueStr, status);
            }catch (Exception e){
                log.error("Value is not text for TextAttribute: " + attrId + " = " + valueStr);
            }
        } else if (type == AttributeType.SELECT && attr instanceof StringSelectAttribute) {
            try {
                ((StringSelectAttribute) attr).updateValue(valueStr, status);
            }catch (Exception e){
                log.error("Value is not text for StringSelectAttribute: " + attrId + " = " + valueStr);
            }
        }
    }
}

