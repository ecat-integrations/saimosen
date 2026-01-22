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
import com.ecat.integration.ModbusIntegration.EndianConverter.AbstractEndianConverter;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;

/**
 * SMS 8300 NOx自动分析仪 - Saimosen
 * 按最新Modbus协议实现
 *
 * @version 1.0.0
 * @author caohongbo
 */
public class NO2Device extends SmsDeviceBase {

    // 数据段配置
    private static final Map<String, DataSegment> SEGMENT_CONFIG = new HashMap<>();
    static {
        // 根据022-通讯协议-NOx2025_0519.pdf和022-四参数仪器校准通讯协议.pdf
        // NOx设备参数段配置
        SEGMENT_CONFIG.put("float_params", new DataSegment(0, 54, "float参数"));  // 0-26地址，27个float参数, 54个寄存器
        SEGMENT_CONFIG.put("u16_params", new DataSegment(58, 28, "U16参数"));     // 58-85地址，28个U16参数， 28个寄存器

        // 校准通讯协议 - 1000~1006地址段（与CO设备相同的校准协议）
        SEGMENT_CONFIG.put("zero_calibration_start", new DataSegment(0x3E8, 1, "零点校准开始"));      // 1000 - 只写
        SEGMENT_CONFIG.put("zero_calibration_confirm", new DataSegment(0x3E9, 1, "零点校准确认"));    // 1001 - 只写
        SEGMENT_CONFIG.put("zero_calibration_cancel", new DataSegment(0x3EA, 1, "零点校准取消"));     // 1002 - 只写
        SEGMENT_CONFIG.put("span_calibration_start", new DataSegment(0x3EB, 1, "跨度校准开始"));       // 1003 - 可读可写
        SEGMENT_CONFIG.put("span_calibration_confirm", new DataSegment(0x3EC, 1, "跨度校准确认"));     // 1004 - 只写
        SEGMENT_CONFIG.put("span_calibration_cancel", new DataSegment(0x3ED, 1, "跨度校准取消"));      // 1005 - 只写
        SEGMENT_CONFIG.put("calibration_status", new DataSegment(0x3EE, 1, "校准状态"));              // 1006 - 可读
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

    public NO2Device(Map<String, Object> config) {
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
        stop();
        super.release();
    }

    private void createAttributes() {
        // 第一组参数（float类型）- 27个参数，与updateFloatAttributes顺序一致
        // NO浓度
        setAttribute(new NumericAttribute(
                "no", AttributeClass.NO, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false));
        // NO2浓度
        setAttribute(new NumericAttribute(
                "no2", AttributeClass.NO2, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false));
        // NOx浓度
        setAttribute(new NumericAttribute(
                "nox", AttributeClass.NOX, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false));
        // NO测量电压
        setAttribute(new NumericAttribute(
                "no_measure_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                2, false, false));
        // NOx测量电压
        setAttribute(new NumericAttribute(
                "nox_measure_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                2, false, false));
        // 样气压力
        setAttribute(new NumericAttribute(
                "sample_press", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA,
                2, false, false));
        // 样气温度
        setAttribute(new NumericAttribute(
                "sample_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        // 样气流量
        setAttribute(new NumericAttribute(
                "sample_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
                1, false, false));
        // 泵压力
        setAttribute(new NumericAttribute(
                "pump_press", AttributeClass.PUMP_P, PressureUnit.KPA, PressureUnit.KPA,
                2, false, false));
        // 反应室压力
        setAttribute(new NumericAttribute(
                "chamber_press", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA,
                2, false, false));
        // 臭氧流量
        setAttribute(new NumericAttribute(
                "o3_flow", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
                1, false, false));
        // NO浓度斜率
        setAttribute(new NumericAttribute(
                "no_slope", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, true));
        // NO浓度截距
        setAttribute(new NumericAttribute(
                "no_intercept", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, true));
        // NOx浓度斜率
        setAttribute(new NumericAttribute(
                "nox_slope", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, true));
        // NOx浓度截距
        setAttribute(new NumericAttribute(
                "nox_intercept", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, true));
        // 样气压力修正值
        setAttribute(new NumericAttribute(
                "sample_press_corr", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA,
                2, false, true));
        // 泵压力修正值
        setAttribute(new NumericAttribute(
                "pump_press_corr", AttributeClass.PUMP_P, PressureUnit.KPA, PressureUnit.KPA,
                2, false, true));
        // 反应室压力修正值
        setAttribute(new NumericAttribute(
                "chamber_press_corr", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA,
                2, false, true));
        // 样气温度修正值
        setAttribute(new NumericAttribute(
                "sample_temp_corr", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, true));
        // 样气流量修正值
        setAttribute(new NumericAttribute(
                "sample_flow_corr", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
                1, false, true));
        // 钼炉温度修正值
        setAttribute(new NumericAttribute(
                "mo_furnace_temp_corr", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, true));
        // 钼炉加热温度设定值
        setAttribute(new NumericAttribute(
                "mo_furnace_temp_setting", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, true));
        // 反应室加热温度设定值
        setAttribute(new NumericAttribute(
                "chamber_temp_setting", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, true));
        // 臭氧流量修正值
        setAttribute(new NumericAttribute(
                "o3_flow_corr", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
                1, false, true));
        // NO原始浓度
        setAttribute(new NumericAttribute(
                "no_raw_concentration", AttributeClass.NO, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false));
        // NOx原始浓度
        setAttribute(new NumericAttribute(
                "nox_raw_concentration", AttributeClass.NOX, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, false, false));
        // 零点电压
        setAttribute(new NumericAttribute(
                "zero_check_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                2, false, false));

        // 第二组参数（U16类型）- 28个参数，与updateU16Attributes顺序一致
        // 仪器地址
        setAttribute(new NumericAttribute(
                "device_address", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        // 仪器状态
        setAttribute(new NumericAttribute(
                "device_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        // PMT高压设定值
        setAttribute(new NumericAttribute(
                "pmt_high_volt_setting", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, true));
        // 样气温度电压
        setAttribute(new NumericAttribute(
                "sample_temp_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 样气压力电压
        setAttribute(new NumericAttribute(
                "sample_press_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 泵压力电压
        setAttribute(new NumericAttribute(
                "pump_press_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 反应室压力电压
        setAttribute(new NumericAttribute(
                "chamber_press_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 机箱温度电压
        setAttribute(new NumericAttribute(
                "case_temp_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // PMT温度电压
        setAttribute(new NumericAttribute(
                "pmt_temp_volt", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 机箱温度
        setAttribute(new NumericAttribute(
                "case_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        // 钼炉加热温度
        setAttribute(new NumericAttribute(
                "mo_furnace_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        // PMT温度
        setAttribute(new NumericAttribute(
                "pmt_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        // 反应室温度
        setAttribute(new NumericAttribute(
                "chamber_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, false, false));
        // 12V电压值
        setAttribute(new NumericAttribute(
                "voltage_12v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 15V电压值
        setAttribute(new NumericAttribute(
                "voltage_15v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 5V电压值
        setAttribute(new NumericAttribute(
                "voltage_5v", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // 3.3V电压值
        setAttribute(new NumericAttribute(
                "voltage_3v3", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT,
                1, false, false));
        // NO/NOx切换阀状态
        setAttribute(new NumericAttribute(
                "no_nox_switch_valve_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        // 采样校准阀状态
        setAttribute(new NumericAttribute(
                "sample_cal_valve_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        // 自动零点阀继电器状态
        setAttribute(new NumericAttribute(
                "auto_zero_value_relay_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        // 内置泵状态
        setAttribute(new NumericAttribute(
                "builtin_pump_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        // 机箱风扇状态
        setAttribute(new NumericAttribute(
                "case_fan_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        // 钼炉加热状态
        setAttribute(new NumericAttribute(
                "mo_furnace_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        // 反应室加热状态
        setAttribute(new NumericAttribute(
                "chamber_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true));
        // 报警信息
        setAttribute(new NumericAttribute(
                "alarm_info", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        // 故障代码
        setAttribute(new NumericAttribute(
                "fault_code", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));
        // PMT高压读取值
        setAttribute(new NumericAttribute(
                "pmt_high_volt_read", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));

        // 校准相关属性
        setAttribute(new NumericAttribute(
                "calibration_concentration", AttributeClass.NO2, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                3, true, true));
        setAttribute(new NumericAttribute(
                "calibration_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, true, true));

        // 校准命令属性
        GasDeviceCommandAttribute commandAttr = new GasDeviceCommandAttribute("dispatch_command",
                AttributeClass.DISPATCH_COMMAND, new GasDeviceCommandAttribute.NO2CommandConfigFactory());
        commandAttr.setModbusSource(modbusSource);
        commandAttr.addDependencyAttribute((NumericAttribute) getAttrs().get("calibration_concentration"));
        commandAttr.setDeviceInstance(this); // 设置设备引用，用于防止竞态条件
        setAttribute(commandAttr);
    }

    /**
     * 并行读取
     * 1. 并行读取：多个数据段同时读取，提升性能
     * 2. 统一处理：所有数据读取完成后统一处理
     */
    private CompletableFuture<Boolean> readAndUpdate() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            
            // 并行读取所有数据段，每个段独立处理失败情况
            CompletableFuture<SegmentData> floatDataFuture = readSegment(source, "float_params")
                    .thenApply(this::parseFloatData)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("NO2Device " + getId() + " - Float data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });
            
            CompletableFuture<SegmentData> u16DataFuture = readSegment(source, "u16_params")
                    .thenApply(this::parseU16Data)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("NO2Device " + getId() + " - U16 data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });
            
            // 读取可读的校准参数
            CompletableFuture<SegmentData> spanCalibConcentrationFuture = readSegment(source, "span_calibration_start")
                    .thenApply(this::parseSpanCalibrationConcentration)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("NO2Device " + getId() + " - Span calibration data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });
            
            CompletableFuture<SegmentData> instrumentCalibStatusFuture = readSegment(source, "calibration_status")
                    .thenApply(this::parseInstrumentCalibrationStatus)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("NO2Device " + getId() + " - Calibration status data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });

            // 等待所有数据读取完成，但不要求全部成功
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
                                log.info("NO2Device " + getId() + " - All segments updated successfully, device status: " + deviceStatus.getStatusName());
                            } else {
                                log.warn("NO2Device " + getId() + " - Partial success: " + successCount + "/" + totalCount + " segments updated, device status: " + deviceStatus.getStatusName());
                            }
                            
                            // 只要有任何一个数据段成功，就返回true
                            return successCount > 0;
                            
                } catch (Exception e) {
                            log.error("NO2Device data processing failed: " + e.getMessage());
                            setAllAttributesStatus(AttributeStatus.MALFUNCTION);
                    return false;
                }
            });
        }).exceptionally(throwable -> {
            log.error("NO2Device communication failed: " + throwable.getMessage());
            setAllAttributesStatus(AttributeStatus.MALFUNCTION);
            return false;
        });
    }

    /**
     * 读取指定数据段
     * @param source Modbus源
     * @param segmentName 段名称
     * @return 原始数据
     */
    private CompletableFuture<short[]> readSegment(ModbusSource source, String segmentName) {
        DataSegment segment = SEGMENT_CONFIG.get(segmentName);
        if (segment == null) {
            log.error("Segment not found: " + segmentName);
            return CompletableFuture.completedFuture(new short[0]);
        }
        
        return source.readHoldingRegisters(segment.startAddress, segment.count)
                .thenApply(response -> {
                    short[] data = response.getShortData();
                    if (data == null) {
                        log.warn("NO2Device " + getId() + " - " + segmentName + " data is null, using default values");
                        data = new short[segment.count];
                    }
                    return data;
                });
    }

    /**
     * 解析float数据段
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseFloatData(short[] rawData) {
        double[] values = new double[rawData.length / 2]; // 每个float参数占用2个寄存器
        for (int i = 0; i < values.length; i++) {
            values[i] = Tools.convertLittleEndianByteSwapToFloat(rawData[i*2+1], rawData[i*2]);
        }
        return new SegmentData("float_params", values);
    }

    /**
     * 解析U16数据段
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseU16Data(short[] rawData) {
        double[] values = new double[rawData.length]; // 每个U16参数占用1个寄存器
        for (int i = 0; i < values.length; i++) {
            values[i] = rawData[i] & 0xFFFF; // 转换为无符号整数
        }
        return new SegmentData("u16_params", values);
    }

    /**
     * 解析跨度校准浓度数据段
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseSpanCalibrationConcentration(short[] rawData) {
        double[] values = new double[1];
        if (rawData.length > 0) {
            values[0] = rawData[0]; // 跨度校准浓度值
        }
        return new SegmentData("calibration_concentration", values);
    }

    /**
     * 解析仪器校准状态数据段
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseInstrumentCalibrationStatus(short[] rawData) {
        double[] values = new double[1];
        if (rawData.length > 0) {
            values[0] = rawData[0]; // 校准状态值
        }
        return new SegmentData("calibration_status", values);
    }

    /**
     * 处理校准状态
     * @param calibData 校准数据
     */
    private void processCalibrationStatus(SegmentData calibData) {
        if (calibData != null && calibData.values.length > 0) {
            short calibrationStatus = (short) calibData.values[0];
            deviceStatus = parseDeviceStatus(calibrationStatus);
            log.info("NO2Device " + getId() + " - Calibration status: " + calibrationStatus + ", device status: " + deviceStatus.getStatusName());
        }
    }

    /**
     * 更新所有属性
     * @param floatData float数据段
     * @param u16Data U16数据段
     * @param spanCalibConcentration 跨度校准浓度
     * @param instrumentCalibStatus 仪器校准状态
     */
    private void updateAllAttributes(SegmentData floatData, SegmentData u16Data, 
                                   SegmentData spanCalibConcentration, SegmentData instrumentCalibStatus) {
        
        // 根据设备状态映射属性状态
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

    /**
     * 更新float类型属性
     * @param values 数值数组
     * @param status 属性状态
     */
    private void updateFloatAttributes(double[] values, AttributeStatus status) {
        String[] attrNames = {
            "no", "no2", "nox", "no_measure_volt", "nox_measure_volt", "sample_press", "sample_temp", "sample_flow",
            "pump_press", "chamber_press", "o3_flow", "no_slope", "no_intercept", "nox_slope", "nox_intercept",
            "sample_press_corr", "pump_press_corr", "chamber_press_corr", "sample_temp_corr", "sample_flow_corr",
            "mo_furnace_temp_corr", "mo_furnace_temp_setting", "chamber_temp_setting", "o3_flow_corr",
            "no_raw_concentration", "nox_raw_concentration", "zero_check_volt"
        };

        for (int i = 0; i < attrNames.length && i < values.length; i++) {
            updateAttribute(attrNames[i], values[i], status);
        }
    }

    /**
     * 更新U16类型属性
     * @param values 数值数组
     * @param status 属性状态
     */
    private void updateU16Attributes(double[] values, AttributeStatus status) {
        updateAttribute("device_address", values[0], status);
        updateAttribute("device_status", values[1], status);
        updateAttribute("pmt_high_volt_setting", values[2], status);
        updateAttribute("sample_temp_volt", values[3] / 10.0, status);
        updateAttribute("sample_press_volt", values[4] / 10.0, status);
        updateAttribute("pump_press_volt", values[5] / 10.0, status);
        updateAttribute("chamber_press_volt", values[6] / 10.0, status);
        updateAttribute("case_temp_volt", values[7] / 10.0, status);
        updateAttribute("pmt_temp_volt", values[8] / 10.0, status);
        updateAttribute("case_temp", values[9] / 10.0, status);
        updateAttribute("mo_furnace_temp", values[10], status);
        updateAttribute("pmt_temp", values[11], status);
        updateAttribute("chamber_temp", values[12], status);
        updateAttribute("voltage_12v", values[13], status);
        updateAttribute("voltage_15v", values[14], status);
        updateAttribute("voltage_5v", values[15], status);
        updateAttribute("voltage_3v3", values[16], status);
        updateAttribute("no_nox_switch_valve_status", values[17], status);
        updateAttribute("sample_cal_valve_status", values[18], status);
        updateAttribute("auto_zero_value_relay_status", values[19], status);
        updateAttribute("builtin_pump_status", values[20], status);
        updateAttribute("case_fan_status", values[21], status);
        updateAttribute("cooling_fan_status", values[22], status);
        updateAttribute("mo_furnace_status", values[23], status);
        updateAttribute("chamber_status", values[24], status);
        updateAttribute("alarm_info", values[25], status);
        updateAttribute("fault_code", values[26], status);
        updateAttribute("pmt_high_volt_read", values[27], status);

    }

    /**
     * 更新校准相关属性
     * @param spanCalibConcentration 跨度校准浓度
     * @param instrumentCalibStatus 仪器校准状态
     * @param status 属性状态
     */
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
            log.debug("NO2Device " + getId() + " - Using written calibration value " + lastWrittenCalibrationValue 
                + " instead of read value " + spanCalibValue + " (write protection active)");
            spanCalibValue = lastWrittenCalibrationValue;
        }
        
        double calibrationValue = getCalibrationValue(deviceStatus, spanCalibValue);
        updateAttribute("calibration_concentration", calibrationValue, status);
    }

    /**
     * 设置所有属性状态
     * @param status 属性状态
     */
    private void setAllAttributesStatus(AttributeStatus status) {
        getAttrs().values().forEach(attr -> attr.setStatus(status));
    }
    
    /**
     * 设置float类型属性的状态
     * @param status 属性状态
     */
    private void setFloatAttributesStatus(AttributeStatus status) {
        // float属性包括：NO, NO2, NOX, NO_MEASURE_VOLT, NOX_MEASURE_VOLT, SAMPLE_PRESS, SAMPLE_TEMP, SAMPLE_FLOW, PUMP_PRESS, CHAMBER_PRESS, O3_FLOW, NO_SLOPE, NO_INTERCEPT, NOX_SLOPE, NOX_INTERCEPT等
        String[] floatAttrNames = {"no", "no2", "nox", "no_measure_volt", "nox_measure_volt", "sample_press", "sample_temp", "sample_flow", "pump_press", "chamber_press", "o3_flow", "no_slope", "no_intercept", "nox_slope", "nox_intercept", "sample_press_corr", "pump_press_corr", "chamber_press_corr", "sample_temp_corr", "sample_flow_corr", "mo_furnace_temp_corr", "mo_furnace_temp_setting", "chamber_temp_setting", "o3_flow_corr", "no_raw_concentration", "nox_raw_concentration", "zero_check_volt", "pmt_temp"};
        
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
        // U16属性包括：device_address, device_status, pmt_high_volt_setting等
        String[] u16AttrNames = {"device_address", "device_status", "pmt_high_volt_setting", "sample_temp_volt", "sample_press_volt", "pump_press_volt", "chamber_press_volt", "chamber_temp", "voltage_12v", "voltage_15v", "voltage_5v", "voltage_3v3", "no_nox_switch_valve_status", "sample_cal_valve_status", "auto_zero_value_relay_status", "builtin_pump_status", "case_fan_status", "mo_furnace_status", "chamber_status", "alarm_info", "fault_code", "pmt_high_volt_read"};

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
        String[] calibAttrNames = {"calibration_concentration", "calibration_status"};
        
        for (String attrName : calibAttrNames) {
            AttributeAbility<?> attr = getAttrs().get(attrName);
            if (attr != null) {
                attr.setStatus(status);
            }
        }
    }

    /**
     * 解析设备状态寄存器值
     * @param statusRegister 寄存器值
     * @return 设备状态枚举
     */
    private DeviceStatus parseDeviceStatus(short statusRegister) {
        // 根据022四参数仪器校准通讯协议
        // 0 = 正常测量模式
        // 1 = 零点校准模式  
        // 2 = 跨度校准模式
        switch (statusRegister) {
            case 0: return DeviceStatus.MEASURE;           // 正常测量模式
            case 1: return DeviceStatus.ZERO_CALIBRATION;  // 零点校准模式
            case 2: return DeviceStatus.SPAN_CALIBRATION;  // 跨度校准模式
            default: return DeviceStatus.UNKNOWN;
        }
    }

    /**
     * 将设备状态映射为属性状态
     * @param deviceStatus 设备状态
     * @return 属性状态
     */
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
    
    /**
     * 根据设备状态获取校准浓度数值
     * @param deviceStatus 设备状态
     * @param spanCalibConcentrationValue 跨度校准浓度值
     * @return 校准浓度数值
     */
    private double getCalibrationValue(DeviceStatus deviceStatus, double spanCalibConcentrationValue) {
        switch (deviceStatus) {
            case ZERO_CALIBRATION:
            case ZERO:
            case MEASURE:
                return 0.0;
            case SPAN_CALIBRATION:
            case SPAN:
                return spanCalibConcentrationValue != 0.0 ? spanCalibConcentrationValue : 400.0;
            default:
                return 0.0;
        }
    }

    private void updateAttribute(String attrName, double value, AttributeStatus status) {
        if (getAttrs().containsKey(attrName)) {
            ((NumericAttribute) getAttrs().get(attrName)).updateValue(value, status);
        }
    }

    /**
     * 开始零点校准
     * @param concentration 校准浓度（零点校准为0）
     * @return 操作结果
     */
    public CompletableFuture<Boolean> startZeroCalibration(double concentration) {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 写入零点校准模式到0x3E8
            return source.writeRegister(0x3E8, 0)
                    .thenCompose(v -> {
                        // 写入零点校准浓度到0x3E9
                        return source.writeRegister(0x3E9, (int) concentration);
                    })
                    .thenCompose(v -> {
                        // 写入零点校准命令到0x3EA
                        return source.writeRegister(0x3EA, 0);
                    })
                    .thenApply(v -> {
                        log.info("NO2Device " + getId() + " - Zero calibration started with concentration: " + concentration);
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("NO2Device zero calibration failed: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 开始跨度校准
     * @param concentration 校准浓度
     * @return 操作结果
     */
    public CompletableFuture<Boolean> startSpanCalibration(double concentration) {
        // 设置写入标志，防止竞态条件
        isWritingCalibration = true;
        lastWrittenCalibrationValue = concentration;
        lastCalibrationWriteTime = System.currentTimeMillis();
        
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 写入跨度校准模式到0x3E8
            return source.writeRegister(0x3E8, 2)
                    .thenCompose(v -> {
                        // 写入跨度校准浓度到0x3EB
                        return source.writeRegister(0x3EB, (int) concentration);
                    })
                    .thenCompose(v -> {
                        // 写入跨度校准命令到0x3EC
                        return source.writeRegister(0x3EC, 1);
                    })
                    .thenApply(v -> {
                        log.info("NO2Device " + getId() + " - Span calibration started with concentration: " + concentration);
                        // 写入完成后，延迟清除标志，确保读取操作有足够时间完成
                        // 标志会在保护期结束后自动失效（通过时间判断）
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("NO2Device span calibration failed: " + throwable.getMessage());
            // 写入失败时清除标志
            isWritingCalibration = false;
            return false;
        }).whenComplete((result, throwable) -> {
            // 无论成功或失败，在保护期结束后清除写入标志
            // 这里不立即清除，而是依赖时间窗口机制
            // 如果需要立即清除，可以设置一个定时任务
        });
    }

    /**
     * 停止校准
     * @return 操作结果
     */
    public CompletableFuture<Boolean> stopCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 写入停止校准命令到0x3E8
            return source.writeRegister(0x3E8, 0)
                    .thenApply(v -> {
                        log.info("NO2Device " + getId() + " - Calibration stopped");
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("NO2Device stop calibration failed: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 读取跨度校准浓度
     * @return 跨度校准浓度
     */
    public CompletableFuture<Double> readSpanCalibrationConcentration() {
        return modbusSource.readHoldingRegisters(0x3EB, 1)
                .thenApply(response -> {
                    short[] data = response.getShortData();
                    if (data != null && data.length > 0) {
                        double concentration = data[0];
                        log.info("NO2Device " + getId() + " - Span calibration concentration: " + concentration);
                        return concentration;
                    }
                    return 0.0;
                })
                .exceptionally(throwable -> {
                    log.error("NO2Device read span calibration concentration failed: " + throwable.getMessage());
                    return 0.0;
                });
    }

    /**
     * 确认零点校准
     * @return 操作结果
     */
    public CompletableFuture<Boolean> confirmZeroCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            return source.writeRegister(0x3E9, 0)
                    .thenApply(v -> {
                        log.info("NO2Device " + getId() + " - Zero calibration confirmed");
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("NO2Device confirm zero calibration failed: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 取消零点校准
     * @return 操作结果
     */
    public CompletableFuture<Boolean> cancelZeroCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            return source.writeRegister(0x3EA, 0)
                    .thenApply(v -> {
                        log.info("NO2Device " + getId() + " - Zero calibration cancelled");
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("NO2Device cancel zero calibration failed: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 确认跨度校准
     * @return 操作结果
     */
    public CompletableFuture<Boolean> confirmSpanCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            return source.writeRegister(0x3EC, 400)
                    .thenApply(v -> {
                        log.info("NO2Device " + getId() + " - Span calibration confirmed");
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("NO2Device confirm span calibration failed: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 取消跨度校准
     * @return 操作结果
     */
    public CompletableFuture<Boolean> cancelSpanCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            return source.writeRegister(0x3ED, 400)
                    .thenApply(v -> {
                        log.info("NO2Device " + getId() + " - Span calibration cancelled");
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("NO2Device cancel span calibration failed: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 读取校准状态
     * @return 校准状态
     */
    public CompletableFuture<Short> readCalibrationStatus() {
        return modbusSource.readHoldingRegisters(0x3EE, 1)
                .thenApply(response -> {
                    short[] data = response.getShortData();
                    if (data != null && data.length > 0) {
                        short status = data[0];
                        log.info("NO2Device " + getId() + " - Calibration status: " + status);
                        return status;
                    }
                    return (short) 0;
                })
                .exceptionally(throwable -> {
                    log.error("NO2Device read calibration status failed: " + throwable.getMessage());
                    return (short) 0;
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
        log.debug("NO2Device " + getId() + " - Marked calibration write: " + concentration);
    }

    /**
     * 清除校准写入标记（可选，通常依赖时间窗口自动清除）
     */
    public void clearCalibrationWriteMark() {
        isWritingCalibration = false;
    }

    /**
     * 数据段配置类
     * 封装数据段的配置信息
     */
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

    /**
     * 段数据封装类
     * 封装解析后的段数据
     */
    private static class SegmentData {
        final String segmentName; // 段名称
        final double[] values;    // 数值数组

        SegmentData(String segmentName, double[] values) {
            this.segmentName = segmentName;
            this.values = values;
        }
    }
} 
