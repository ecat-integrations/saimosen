package com.ecat.integration.SaimosenIntegration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.State.AttributeAbility;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;
import com.ecat.integration.ModbusIntegration.Tools;

/**
 * SMS 8400 O3自动分析仪 - Saimosen
 * 按最新Modbus协议实现
 *
 * @version 1.0.0
 * @author caohongbo
 */
public class O3Device extends SmsDeviceBase {

    // 数据段配置
    private static final Map<String, DataSegment> SEGMENT_CONFIG = new HashMap<>();
    static {
        // 根据022-通讯协议-O32025_0519.pdf和022-四参数仪器校准通讯协议.pdf
        // O3设备参数段配置
        SEGMENT_CONFIG.put("float_params", new DataSegment(0, 40, "float参数"));  // 0-19地址，20个float参数，40个寄存器
        SEGMENT_CONFIG.put("u16_params", new DataSegment(40, 18, "U16参数"));     // 40-57地址，18个U16参数，18个寄存器

        // 校准通讯协议 - 1000~1006地址段（与CO设备相同的校准协议）
        SEGMENT_CONFIG.put("zero_calibration_start", new DataSegment(0x3E8, 1, "零点校准开始"));  // 1000 - 只写
        SEGMENT_CONFIG.put("zero_calibration_confirm", new DataSegment(0x3E9, 1, "零点校准确认"));  // 1001 - 只写
        SEGMENT_CONFIG.put("zero_calibration_cancel", new DataSegment(0x3EA, 1, "零点校准取消"));  // 1002 - 只写
        SEGMENT_CONFIG.put("span_calibration_start", new DataSegment(0x3EB, 1, "跨度校准开始"));   // 1003 - 可读可写
        SEGMENT_CONFIG.put("span_calibration_confirm", new DataSegment(0x3EC, 1, "跨度校准确认"));  // 1004 - 只写
        SEGMENT_CONFIG.put("span_calibration_cancel", new DataSegment(0x3ED, 1, "跨度校准取消"));  // 1005 - 只写
        SEGMENT_CONFIG.put("calibration_status", new DataSegment(0x3EE, 1, "校准状态"));  // 1006 - 可读
    }

    private DeviceStatus deviceStatus = DeviceStatus.UNKNOWN;
    
    // 防止竞态条件：标记是否正在写入校准浓度
    private volatile boolean isWritingCalibration = false;
    // 保存最近写入的校准浓度值，避免在写入期间被读取的旧值覆盖
    private volatile Double lastWrittenCalibrationValue = null;
    // 写入操作的时间戳，用于判断是否在写入后的短时间内
    private volatile long lastCalibrationWriteTime = 0;
    // 写入保护时间窗口（毫秒），在此时间内使用写入的值而不是读取的值
    private static final long CALIBRATION_WRITE_PROTECTION_MS = 2000; // 2秒保护期

    public O3Device(Map<String, Object> config) {
        super(config);
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        getScheduledExecutor().scheduleWithFixedDelay(this::readAndUpdate, 0, 5, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        // 停止逻辑
    }

    @Override
    public void release() {
        super.release();
    }

    private void createAttributes() {
        // 根据DeviceParamsConfig中的sms-o3配置创建所有属性
        // Float参数（前20个）
        setAttribute(new NumericAttribute(
                "o3", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                3, false, false));
        setAttribute(new NumericAttribute(
                "measure_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                2, false, false));
        setAttribute(new NumericAttribute(
                "ref_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                2, false, false));
        setAttribute(new NumericAttribute(
                "sample_press", AttributeClass.PRESSURE, PressureUnit.PA, PressureUnit.PA,
                2, false, false));
        setAttribute(new NumericAttribute(
                "sample_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        setAttribute(new NumericAttribute(
                "sample_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
                2, false, false));
        setAttribute(new NumericAttribute(
                "pump_press", AttributeClass.PRESSURE, PressureUnit.PA, PressureUnit.PA,
                2, false, false));
        setAttribute(new NumericAttribute(
                "slope", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, false));
        setAttribute(new NumericAttribute(
                "intercept", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, false));
        setAttribute(new NumericAttribute(
                "sample_press_corr", AttributeClass.PRESSURE, PressureUnit.PA, PressureUnit.PA,
                2, false, false));
        setAttribute(new NumericAttribute(
                "pump_press_corr", AttributeClass.PRESSURE, PressureUnit.PA, PressureUnit.PA,
                2, false, false));
        setAttribute(new NumericAttribute(
                "sample_temp_corr", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        setAttribute(new NumericAttribute(
                "sample_flow_corr", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
                2, false, false));
        setAttribute(new NumericAttribute(
                "led_set_current", AttributeClass.CURRENT, NoConversionUnit.of("mA"), NoConversionUnit.of("mA"),
                3, false, false));
        setAttribute(new NumericAttribute(
                "led_current", AttributeClass.CURRENT, NoConversionUnit.of("mA"), NoConversionUnit.of("mA"),
                3, false, false));
        setAttribute(new NumericAttribute(
                "raw_concentration", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                3, false, false));
        // 备用（4个）
        setAttribute(new NumericAttribute(
                "reserve_1", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        setAttribute(new NumericAttribute(
                "reserve_2", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        setAttribute(new NumericAttribute(
                "reserve_3", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        setAttribute(new NumericAttribute(
                "reserve_4", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        
        // U16参数（从地址40开始，18个）
        setAttribute(new NumericAttribute(
                "device_address", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "device_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "uv_amplification", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "sample_temp_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "sample_press_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "pump_press_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "case_temp_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "case_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        setAttribute(new NumericAttribute(
                "voltage_12v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "voltage_15v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "voltage_5v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "voltage_3v3", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "measure_ref_valve_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "sample_cal_valve_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "builtin_pump_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "case_fan_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "alarm_info", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        setAttribute(new NumericAttribute(
                "fault_code", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));

        // 校准相关属性
        setAttribute(new NumericAttribute(
                "calibration_concentration", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false));
        setAttribute(new NumericAttribute(
                "calibration_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));

        // 校准命令属性
        GasDeviceCommandAttribute commandAttr = new GasDeviceCommandAttribute("dispatch_command",
                AttributeClass.DISPATCH_COMMAND, new GasDeviceCommandAttribute.O3CommandConfigFactory());
        commandAttr.setModbusSource(modbusSource);
        commandAttr.addDependencyAttribute((NumericAttribute) getAttrs().get("calibration_concentration"));
        commandAttr.setDeviceInstance(this); // 设置设备引用，用于防止竞态条件
        setAttribute(commandAttr);

        log.info("O3Device " + getId() + " initialized with " + getAttrs().size() + " attributes");
    }

    private CompletableFuture<Boolean> readAndUpdate() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 并行读取所有数据段，每个段独立处理失败情况
            CompletableFuture<SegmentData> floatDataFuture = readSegment(source, "float_params")
                    .thenApply(this::parseFloatData)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("O3Device " + getId() + " - Float data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });
            
            CompletableFuture<SegmentData> u16DataFuture = readSegment(source, "u16_params")
                    .thenApply(this::parseU16Data)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("O3Device " + getId() + " - U16 data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });
            
            CompletableFuture<SegmentData> spanCalibConcentrationFuture = readSegment(source, "span_calibration_start")
                    .thenApply(this::parseSpanCalibrationConcentration)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("O3Device " + getId() + " - Span calibration data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });
            
            CompletableFuture<SegmentData> instrumentCalibStatusFuture = readSegment(source, "calibration_status")
                    .thenApply(this::parseInstrumentCalibrationStatus)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("O3Device " + getId() + " - Calibration status data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });
            
            return CompletableFuture.allOf(floatDataFuture, u16DataFuture, spanCalibConcentrationFuture, instrumentCalibStatusFuture)
                    .thenApply(v -> {
                        try {
                            // 获取所有数据，允许部分为null
                            SegmentData floatData = floatDataFuture.join();
                            SegmentData u16Data = u16DataFuture.join();
                            SegmentData spanCalibConcentration = spanCalibConcentrationFuture.join();
                            SegmentData instrumentCalibStatus = instrumentCalibStatusFuture.join();
                            
                            // 统计成功的数据段
                            int successCount = 0;
                            int totalCount = 4;
                            
                            if (floatData != null) successCount++;
                            if (u16Data != null) successCount++;
                            if (spanCalibConcentration != null) successCount++;
                            if (instrumentCalibStatus != null) successCount++;
                            
                            // 处理校准状态（如果成功读取）
                            if (instrumentCalibStatus != null) {
                                processCalibrationStatus(instrumentCalibStatus);
                            }
                            // 更新所有属性（允许部分数据为null）
                            updateAllAttributes(floatData, u16Data, spanCalibConcentration, instrumentCalibStatus);
                            
                            if (successCount == totalCount) {
                                log.info("O3Device " + getId() + " - All segments updated successfully, device status: " + deviceStatus.getStatusName());
                            } else {
                                log.warn("O3Device " + getId() + " - Partial success: " + successCount + "/" + totalCount + " segments updated, device status: " + deviceStatus.getStatusName());
                            }
                            return successCount > 0; // 只要有任何一个数据段成功，就返回true
                        } catch (Exception e) {
                            log.error("O3Device data processing failed: " + e.getMessage());
                            setAllAttributesStatus(AttributeStatus.MALFUNCTION);
                            return false;
                }
            });
        }).exceptionally(throwable -> {
            log.error("O3Device communication failed: " + throwable.getMessage());
            setAllAttributesStatus(AttributeStatus.MALFUNCTION);
            return false;
        });
    }

    private CompletableFuture<short[]> readSegment(ModbusSource source, String segmentName) {
        DataSegment segment = SEGMENT_CONFIG.get(segmentName);
        if (segment == null) {
            CompletableFuture<short[]> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Unknown segment: " + segmentName));
            return future;
        }
        
        return source.readHoldingRegisters(segment.startAddress, segment.count)
                .thenApply(response -> response.getShortData());
    }

    public SegmentData parseFloatDataforTest(short[] rawData) {
        return parseFloatData(rawData);
    }
    
    public SegmentData parseU16DataforTest(short[] rawData) {
        return parseU16Data(rawData);
    }
    
    private SegmentData parseFloatData(short[] rawData) {
        double[] values = new double[rawData.length / 2];  // 每个float参数占用2个寄存器
        for (int i = 0; i < values.length; i++) {
            values[i] = Tools.convertLittleEndianByteSwapToFloat(rawData[i*2+1], rawData[i*2]);
        }
        return new SegmentData("float_params", values);
    }

    private SegmentData parseU16Data(short[] rawData) {
        double[] values = new double[rawData.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = rawData[i] & 0xFFFF; // 转换为无符号整数
        }
        return new SegmentData("u16_params", values);
    }

    private SegmentData parseSpanCalibrationConcentration(short[] rawData) {
        double[] values = new double[1];
        values[0] = rawData[0] & 0xFFFF; // 跨度校准浓度
        return new SegmentData("span_calibration_start", values);
    }

    private SegmentData parseInstrumentCalibrationStatus(short[] rawData) {
        double[] values = new double[1];
        values[0] = rawData[0] & 0xFFFF; // 校准状态
        return new SegmentData("calibration_status", values);
    }

    private void processCalibrationStatus(SegmentData calibData) {
        if (calibData != null && calibData.values.length > 0) {
            short calibStatus = (short) calibData.values[0];
            deviceStatus = parseDeviceStatus(calibStatus);
            log.info("O3Device " + getId() + " - Calibration status: " + calibStatus + ", device status: " + deviceStatus.getStatusName());
        }
    }

    private void updateAllAttributes(SegmentData floatData, SegmentData u16Data, 
                                   SegmentData spanCalibConcentration, SegmentData instrumentCalibStatus) {
        AttributeStatus baseStatus = mapToAttributeStatus(deviceStatus);

        // 更新float属性（如果数据可用）
        if (floatData != null) {
            updateFloatAttributes(floatData.values, baseStatus);
        } else {
            // 如果float数据不可用，设置相关属性为故障状态
            setFloatAttributesStatus(AttributeStatus.MALFUNCTION);
        }

        // 更新U16属性（如果数据可用）
        if (u16Data != null) {
            updateU16Attributes(u16Data.values, baseStatus);
        } else {
            // 如果U16数据不可用，设置相关属性为故障状态
            setU16AttributesStatus(AttributeStatus.MALFUNCTION);
        }

        // 更新校准属性（如果数据可用）
        if (spanCalibConcentration != null || instrumentCalibStatus != null) {
            updateCalibrationAttributes(spanCalibConcentration, instrumentCalibStatus, baseStatus);
        } else {
            // 如果校准数据不可用，设置相关属性为故障状态
            setCalibrationAttributesStatus(AttributeStatus.MALFUNCTION);
        }

        // 设置所有属性状态
        setAllAttributesStatus(baseStatus);
        publicAttrsState();
    }

    private void updateFloatAttributes(double[] values, AttributeStatus status) {
        String[] floatAttrNames = {"o3", "measure_volt", "ref_volt", "sample_press", "sample_temp", "sample_flow", "pump_press", "slope", "intercept", "sample_press_corr", "pump_press_corr", "sample_temp_corr", "sample_flow_corr", "led_set_current", "led_current", "raw_concentration", "reserve_1", "reserve_2", "reserve_3", "reserve_4"};
        
        for (int i = 0; i < Math.min(values.length, floatAttrNames.length); i++) {
            updateAttribute(floatAttrNames[i], values[i], status);
        }
    }

    private void updateU16Attributes(double[] values, AttributeStatus status) {
        // 根据O3设备协议，某些电压值需要除以10进行单位转换
        updateAttribute("device_address", values[0], status);
        updateAttribute("device_status", values[1], status);
        updateAttribute("uv_amplification", values[2], status);
        updateAttribute("sample_temp_volt", values[3] / 10.0, status); // 除以10转换为mV
        updateAttribute("sample_press_volt", values[4] / 10.0, status); // 除以10转换为mV
        updateAttribute("pump_press_volt", values[5] / 10.0, status); // 除以10转换为mV
        updateAttribute("case_temp_volt", values[6] / 10.0, status); // 除以10转换为mV
        updateAttribute("case_temp", values[7] / 10.0, status); // 除以10转换为℃
        updateAttribute("voltage_12v", values[8], status);
        updateAttribute("voltage_15v", values[9], status);
        updateAttribute("voltage_5v", values[10], status);
        updateAttribute("voltage_3v3", values[11], status);
        updateAttribute("measure_ref_valve_status", values[12], status);
        updateAttribute("sample_cal_valve_status", values[13], status);
        updateAttribute("builtin_pump_status", values[14], status);
        updateAttribute("case_fan_status", values[15], status);
        updateAttribute("alarm_info", values[16], status);
        updateAttribute("fault_code", values[17], status);
    }

    private void updateCalibrationAttributes(SegmentData spanCalibConcentration, 
                                           SegmentData instrumentCalibStatus, AttributeStatus status) {
        // 更新校准状态属性
        if (instrumentCalibStatus != null && instrumentCalibStatus.values.length > 0) {
            updateAttribute("calibration_status", instrumentCalibStatus.values[0], status);
        }

        // 更新校准浓度属性
        // 防止竞态条件：如果正在写入或刚写入不久，使用写入的值而不是读取的值
        double spanCalibValue = spanCalibConcentration != null && spanCalibConcentration.values.length > 0 
            ? spanCalibConcentration.values[0] : 0.0;
        
        // 检查是否在写入保护期内
        long currentTime = System.currentTimeMillis();
        boolean inWriteProtection = isWritingCalibration || 
            (lastWrittenCalibrationValue != null && 
             (currentTime - lastCalibrationWriteTime) < CALIBRATION_WRITE_PROTECTION_MS);
        
        if (inWriteProtection && lastWrittenCalibrationValue != null) {
            // 在写入保护期内，使用最近写入的值
            log.debug("O3Device " + getId() + " - Using written calibration value " + lastWrittenCalibrationValue 
                + " instead of read value " + spanCalibValue + " (write protection active)");
            spanCalibValue = lastWrittenCalibrationValue;
        }
        
        updateAttribute("calibration_concentration", spanCalibValue, status);
    }

    private void setAllAttributesStatus(AttributeStatus status) {
        getAttrs().values().forEach(attr -> attr.setStatus(status));
    }
    
    /**
     * 设置float类型属性的状态
     * @param status 属性状态
     */
    private void setFloatAttributesStatus(AttributeStatus status) {
        String[] floatAttrNames = {"o3", "measure_volt", "ref_volt", "sample_press", "sample_temp", "sample_flow", "pump_press", "slope", "intercept", "sample_press_corr", "pump_press_corr", "sample_temp_corr", "sample_flow_corr", "led_set_current", "led_current", "raw_concentration", "reserve_1", "reserve_2", "reserve_3", "reserve_4"};
        
        for (String attrName : floatAttrNames) {
            AttributeAbility<?> attr = getAttrs().get(attrName);
            if (attr != null) {
                attr.setStatus(status);
            }
        }
    }
    
    /**
     * 设置U16类型属性的状态
     * @param status 属性状态
     */
    private void setU16AttributesStatus(AttributeStatus status) {
        String[] u16AttrNames = {"device_address", "device_status", "uv_amplification", "sample_temp_volt", "sample_press_volt", "pump_press_volt", "case_temp_volt", "case_temp", "voltage_12v", "voltage_15v", "voltage_5v", "voltage_3v3", "measure_ref_valve_status", "sample_cal_valve_status", "builtin_pump_status", "case_fan_status", "alarm_info", "fault_code"};
        
        for (String attrName : u16AttrNames) {
            AttributeAbility<?> attr = getAttrs().get(attrName);
            if (attr != null) {
                attr.setStatus(status);
            }
        }
    }
    
    /**
     * 设置校准相关属性的状态
     * @param status 属性状态
     */
    private void setCalibrationAttributesStatus(AttributeStatus status) {
        String[] calibAttrNames = {"calibration_concentration", "calibration_mode", "calibration_status"};
        
        for (String attrName : calibAttrNames) {
            AttributeAbility<?> attr = getAttrs().get(attrName);
            if (attr != null) {
                attr.setStatus(status);
            }
        }
    }

    private DeviceStatus parseDeviceStatus(short statusRegister) {
        // 根据校准状态寄存器解析设备状态
        switch (statusRegister) {
            case 0:
                return DeviceStatus.MEASURE;
            case 1:
                return DeviceStatus.ZERO_CALIBRATION;
            case 2:
                return DeviceStatus.SPAN_CALIBRATION;
            default:
                return DeviceStatus.UNKNOWN;
        }
    }

    private AttributeStatus mapToAttributeStatus(DeviceStatus deviceStatus) {
        switch (deviceStatus) {
            case MEASURE:
                return AttributeStatus.NORMAL;
            case ZERO_CALIBRATION:
                return AttributeStatus.ZERO_CALIBRATION;
            case SPAN_CALIBRATION:
                return AttributeStatus.SPAN_CALIBRATION;
            case MAINTENANCE:
                return AttributeStatus.MAINTENANCE;
            case UNKNOWN:
            default:
                return AttributeStatus.EMPTY;
        }
    }

    private double getCalibrationValue(DeviceStatus deviceStatus, double spanCalibConcentrationValue) {
        switch (deviceStatus) {
            case ZERO_CALIBRATION:
                return 0.0; // 零点校准
            case SPAN_CALIBRATION:
                return spanCalibConcentrationValue; // 跨度校准
            default:
                return 0.0;
        }
    }

    private void updateAttribute(String attrName, double value, AttributeStatus status) {
        AttributeAbility<?> attr = getAttrs().get(attrName);
        if (attr instanceof NumericAttribute) {
            NumericAttribute numAttr = (NumericAttribute) attr;
            numAttr.updateValue(value, status);
        }
    }

    public CompletableFuture<Boolean> startZeroCalibration(double concentration) {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            try {
                // 写入零点校准开始命令
                source.writeRegister(SEGMENT_CONFIG.get("zero_calibration_start").startAddress, 1);
                log.info("O3Device " + getId() + " - Zero calibration started");
                return CompletableFuture.completedFuture(true);
            } catch (Exception e) {
                log.error("O3Device " + getId() + " - Failed to start zero calibration: " + e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public CompletableFuture<Boolean> startSpanCalibration(double concentration) {
        // 设置写入标志，防止竞态条件
        isWritingCalibration = true;
        lastWrittenCalibrationValue = concentration;
        lastCalibrationWriteTime = System.currentTimeMillis();
        
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 先写入跨度校准浓度
            return source.writeRegister(SEGMENT_CONFIG.get("span_calibration_start").startAddress, (int) concentration)
                .thenApply(v -> {
                    log.info("O3Device " + getId() + " - Span calibration started with concentration: " + concentration);
                    return true;
                });
        }).exceptionally(throwable -> {
            log.error("O3Device span calibration failed: " + throwable.getMessage());
            // 写入失败时清除标志
            isWritingCalibration = false;
            return false;
        });
    }

    public CompletableFuture<Boolean> stopCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            try {
                // 写入停止校准命令（可以写入0到校准状态寄存器）
                source.writeRegister(SEGMENT_CONFIG.get("calibration_status").startAddress, 0);
                log.info("O3Device " + getId() + " - Calibration stopped");
                return CompletableFuture.completedFuture(true);
            } catch (Exception e) {
                log.error("O3Device " + getId() + " - Failed to stop calibration: " + e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public CompletableFuture<Double> readSpanCalibrationConcentration() {
        return modbusSource.readHoldingRegisters(SEGMENT_CONFIG.get("span_calibration_start").startAddress, 1)
                .thenApply(response -> {
                    short[] data = response.getShortData();
                    return (double) (data[0] & 0xFFFF);
                });
    }

    public CompletableFuture<Boolean> confirmZeroCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            try {
                source.writeRegister(SEGMENT_CONFIG.get("zero_calibration_confirm").startAddress, 1);
                log.info("O3Device " + getId() + " - Zero calibration confirmed");
                return CompletableFuture.completedFuture(true);
            } catch (Exception e) {
                log.error("O3Device " + getId() + " - Failed to confirm zero calibration: " + e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public CompletableFuture<Boolean> cancelZeroCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            try {
                source.writeRegister(SEGMENT_CONFIG.get("zero_calibration_cancel").startAddress, 1);
                log.info("O3Device " + getId() + " - Zero calibration cancelled");
                return CompletableFuture.completedFuture(true);
            } catch (Exception e) {
                log.error("O3Device " + getId() + " - Failed to cancel zero calibration: " + e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public CompletableFuture<Boolean> confirmSpanCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            try {
                source.writeRegister(SEGMENT_CONFIG.get("span_calibration_confirm").startAddress, 1);
                log.info("O3Device " + getId() + " - Span calibration confirmed");
                return CompletableFuture.completedFuture(true);
                } catch (Exception e) {
                log.error("O3Device " + getId() + " - Failed to confirm span calibration: " + e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public CompletableFuture<Boolean> cancelSpanCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            try {
                source.writeRegister(SEGMENT_CONFIG.get("span_calibration_cancel").startAddress, 1);
                log.info("O3Device " + getId() + " - Span calibration cancelled");
                return CompletableFuture.completedFuture(true);
            } catch (Exception e) {
                log.error("O3Device " + getId() + " - Failed to cancel span calibration: " + e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public CompletableFuture<Short> readCalibrationStatus() {
        return modbusSource.readHoldingRegisters(SEGMENT_CONFIG.get("calibration_status").startAddress, 1)
                .thenApply(response -> {
                    short[] data = response.getShortData();
                    return data[0];
                });
    }

    /**
     * 标记校准浓度写入操作（防止竞态条件）
     * 当外部（如GasDeviceCommandAttribute）写入校准浓度时调用此方法
     * @param concentration 写入的校准浓度值
     */
    public void markCalibrationWrite(double concentration) {
        isWritingCalibration = true;
        lastWrittenCalibrationValue = concentration;
        lastCalibrationWriteTime = System.currentTimeMillis();
        log.debug("O3Device " + getId() + " - Marked calibration write: " + concentration);
    }

    /**
     * 清除校准写入标记（可选，通常依赖时间窗口自动清除）
     */
    public void clearCalibrationWriteMark() {
        isWritingCalibration = false;
    }

    private static class DataSegment {
        final int startAddress;  // 起始地址
        final int count;         // 寄存器数量
        final String description; // 描述信息

        DataSegment(int startAddress, int count, String description) {
            this.startAddress = startAddress;
            this.count = count;
            this.description = description;
        }
    }

    private static class SegmentData {
        final String segmentName; // 段名称
        final double[] values;    // 数值数组

        SegmentData(String segmentName, double[] values) {
            this.segmentName = segmentName;
            this.values = values;
        }
    }
} 
