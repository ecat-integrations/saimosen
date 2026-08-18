package com.ecat.integration.SaimosenIntegration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.State.AttributeAbility;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.TextAttribute;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusFloatAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusShortAttribute;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;
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

    private static final String STATUS_PREFIX = "o3";

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

    private static final long CALIBRATION_WRITE_PROTECTION_MS = 2000; // 2秒保护期

    // 防止竞态条件：标记是否正在写入校准浓度
    private volatile boolean isWritingCalibration = false;
    // 保存最近写入的校准浓度值，避免在写入期间被读取的旧值覆盖
    private volatile Double lastWrittenCalibrationValue = null;
    // 写入操作的时间戳，用于判断是否在写入后的短时间内
    private volatile long lastCalibrationWriteTime = 0;

    /** O3 float 段：小端字节交换，读用 {@link Tools#convertLittleEndianByteSwapToFloat}，写见 {@link SmsLittleEndianByteSwapEndianConverter} */
    private final EndianConverter o3FloatEndian = SmsLittleEndianByteSwapEndianConverter.INSTANCE;

    private static final String[] FLOAT_ATTR_NAMES = {
            "o3", "measure_volt", "ref_volt", "sample_press", "sample_temp", "sample_flow", "pump_press",
            "slope", "intercept", "sample_press_corr", "pump_press_corr", "sample_temp_corr", "sample_flow_corr",
            "led_set_current", "led_current", "raw_concentration", "reserve_1", "reserve_2", "reserve_3", "reserve_4"
    };

    private static final String[] U16_ATTR_NAMES = {
            "device_address", "device_status", "uv_amplification", "sample_temp_volt", "sample_press_volt",
            "pump_press_volt", "case_temp_volt", "case_temp", "voltage_12v", "voltage_15v", "voltage_5v",
            "voltage_3v3", "measure_ref_valve_status", "sample_cal_valve_status", "builtin_pump_status",
            "case_fan_status", "alarm_info", "fault_code"
    };

    public O3Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        readFuture = getScheduledExecutor().scheduleWithFixedDelay(this::readAndUpdate, 0, 5, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (readFuture != null) {
            readFuture.cancel(true);
        }
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
        setAttribute(new ModbusFloatAttribute(
                "slope", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, true, modbusSource, (short) 14, o3FloatEndian));
        setAttribute(new ModbusFloatAttribute(
                "intercept", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                3, false, true, modbusSource, (short) 16, o3FloatEndian));
        setAttribute(new ModbusFloatAttribute(
                "sample_press_corr", AttributeClass.PRESSURE, PressureUnit.PA, PressureUnit.PA,
                2, true, true, modbusSource, (short) 18, o3FloatEndian));
        setAttribute(new ModbusFloatAttribute(
                "pump_press_corr", AttributeClass.PRESSURE, PressureUnit.PA, PressureUnit.PA,
                2, true, true, modbusSource, (short) 20, o3FloatEndian));
        setAttribute(new ModbusFloatAttribute(
                "sample_temp_corr", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS,
                1, true, true, modbusSource, (short) 22, o3FloatEndian));
        setAttribute(new ModbusFloatAttribute(
                "sample_flow_corr", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
                2, true, true, modbusSource, (short) 24, o3FloatEndian));
        setAttribute(new ModbusFloatAttribute(
                "led_set_current", AttributeClass.CURRENT, NoConversionUnit.of("mA"), NoConversionUnit.of("mA"),
                3, true, true, modbusSource, (short) 26, o3FloatEndian));
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
        setAttribute(new ModbusShortAttribute(
                "device_address", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true, modbusSource, (short) 40));
        setAttribute(new ModbusShortAttribute(
                "device_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true, modbusSource, (short) 41));
        setAttribute(new ModbusShortAttribute(
                "uv_amplification", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true, modbusSource, (short) 42));
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
        setAttribute(new ModbusShortAttribute(
                "measure_ref_valve_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true, modbusSource, (short) 52));
        setAttribute(new ModbusShortAttribute(
                "sample_cal_valve_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true, modbusSource, (short) 53));
        setAttribute(new ModbusShortAttribute(
                "builtin_pump_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true, modbusSource, (short) 54));
        setAttribute(new ModbusShortAttribute(
                "case_fan_status", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, true, modbusSource, (short) 55));
        setAttribute(new TextAttribute(
                "alarm_info", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                false));
        setAttribute(new NumericAttribute(
                "fault_code", AttributeClass.TEXT, NoConversionUnit.of(""), NoConversionUnit.of(""),
                1, false, false));

        // 校准相关属性
        setAttribute(new NumericAttribute(
                "calibration_concentration", AttributeClass.O3, AirVolumeUnit.PPB, AirVolumeUnit.PPB,
                1, true, true));
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

        // 添加手动状态与通用报警属性（状态优先级：手动 > 报警 > 仪器自动状态）
        addManualStatusAttributes(STATUS_PREFIX);
        addGeneralAlarmAttribute();

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
                            
                            if (floatData == null) {
                                log.warn("O3Device " + getId()
                                        + " - Primary measurement segment failed, skip attribute update (online detection relies on lastUpdated)");
                                return false;
                            }

                            if (instrumentCalibStatus != null) {
                                processCalibrationStatus(instrumentCalibStatus);
                            }
                            updateAllAttributes(floatData, u16Data, spanCalibConcentration, instrumentCalibStatus);
                            commitPollState();

                            if (successCount == totalCount) {
                            } else {
                                log.warn("O3Device " + getId() + " - Partial success: " + successCount + "/" + totalCount + " segments updated, device status: " + deviceStatus.getStatusName());
                            }
                            return true;
                        } catch (Exception e) {
                            log.error("O3Device data processing failed: " + e.getMessage());
                            return false;
                }
            });
        }).exceptionally(throwable -> {
            log.error("O3Device communication failed: " + throwable.getMessage());
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
            // 设备寄存器布局 = BADC(高字在前 + 字内字节交换):此处反转参数 (secondReg, firstReg) 调
            // convertLittleEndianByteSwapToFloat(方法本身=DCBA),净效果 BADC。勿凭方法名误判 CDAB——
            // 实测 O3 [0xBF3E,0xFB7C]→0.374 见 modbus 集成 RealDeviceByteOrderTest#saimosenO3IsBadc。
            values[i] = Tools.convertLittleEndianByteSwapToFloat(rawData[i*2+1], rawData[i*2]);
        }
        DataSegment segment = SEGMENT_CONFIG.get("float_params");
        SmsSegmentLogHelper.logFloatSegment(log, "O3Device", getId(), "float_params",
                segment.startAddress, rawData, FLOAT_ATTR_NAMES, values);
        return new SegmentData("float_params", values, rawData);
    }

    private SegmentData parseU16Data(short[] rawData) {
        double[] values = new double[rawData.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = rawData[i] & 0xFFFF; // 转换为无符号整数
        }
        DataSegment segment = SEGMENT_CONFIG.get("u16_params");
        SmsSegmentLogHelper.logU16Segment(log, "O3Device", getId(), "u16_params",
                segment.startAddress, rawData, U16_ATTR_NAMES, buildO3U16DisplayValues(values));
        return new SegmentData("u16_params", values);
    }

    private SegmentData parseSpanCalibrationConcentration(short[] rawData) {
        double[] values = new double[1];
        values[0] = rawData[0] & 0xFFFF; // 跨度校准浓度
        DataSegment segment = SEGMENT_CONFIG.get("span_calibration_start");
        SmsSegmentLogHelper.logScalarSegment(log, "O3Device", getId(), "span_calibration_start",
                segment.startAddress, rawData, "calibration_concentration", values[0]);
        return new SegmentData("span_calibration_start", values);
    }

    private SegmentData parseInstrumentCalibrationStatus(short[] rawData) {
        double[] values = new double[1];
        values[0] = rawData[0] & 0xFFFF; // 校准状态
        DataSegment segment = SEGMENT_CONFIG.get("calibration_status");
        SmsSegmentLogHelper.logScalarSegment(log, "O3Device", getId(), "calibration_status",
                segment.startAddress, rawData, "calibration_status", values[0]);
        return new SegmentData("calibration_status", values);
    }

    private void processCalibrationStatus(SegmentData calibData) {
        if (calibData != null && calibData.values.length > 0) {
            short calibStatus = (short) calibData.values[0];
            deviceStatus = parseDeviceStatus(calibStatus);
        }
    }

    private static double[] buildO3U16DisplayValues(double[] values) {
        double[] display = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            display[i] = values[i];
        }
        if (display.length > 3) display[3] /= 10.0;
        if (display.length > 4) display[4] /= 10.0;
        if (display.length > 5) display[5] /= 10.0;
        if (display.length > 6) display[6] /= 10.0;
        if (display.length > 7) display[7] /= 10.0;
        return display;
    }

    private void updateAllAttributes(SegmentData floatData, SegmentData u16Data, 
                                   SegmentData spanCalibConcentration, SegmentData instrumentCalibStatus) {
        AttributeStatus autoStatus = mapToAttributeStatus(deviceStatus);
        if (floatData == null && u16Data == null && spanCalibConcentration == null && instrumentCalibStatus == null) {
            autoStatus = AttributeStatus.MALFUNCTION;
        }

        // 先更新报警，再判定状态（优先级：手动 > 报警 > 仪器自动状态）
        if (u16Data != null) {
            updateAlarmInfo(u16Data.values[16], SmsAlarmMessages.O3_ACTIVE, AttributeStatus.NORMAL);
        }

        AttributeStatus baseStatus = determineAttributeStatus(autoStatus, STATUS_PREFIX + "_manual_status", "general_alarm");
        updateReadonlyStatusAttribute(STATUS_PREFIX + "_status", baseStatus);

        updateFloatAttributes(floatData, baseStatus);

        if (u16Data != null) {
            updateU16Attributes(u16Data.values, baseStatus);
        }

        if (spanCalibConcentration != null || instrumentCalibStatus != null) {
            updateCalibrationAttributes(spanCalibConcentration, instrumentCalibStatus, baseStatus);
        }
        // 属性更新完成后再同步设备级显示状态，避免影响校准浓度等依赖仪器模式的计算
        syncDeviceStatusIfOverridden(autoStatus, baseStatus);
    }

    private void updateFloatAttributes(SegmentData floatData, AttributeStatus status) {
        double[] values = floatData.values;
        short[] raw = floatData.rawRegisters;
        for (int i = 0; i < Math.min(values.length, FLOAT_ATTR_NAMES.length); i++) {
            String name = FLOAT_ATTR_NAMES[i];
            AttributeAbility<?> attr = getAttrs().get(name);
            if (attr instanceof ModbusFloatAttribute && raw != null && i * 2 + 1 < raw.length) {
                ModbusFloatAttribute modbusAttr = (ModbusFloatAttribute) attr;
                modbusAttr.updateValue(raw[i * 2], raw[i * 2 + 1]);
                modbusAttr.setStatus(status);
            } else {
                updateAttribute(name, values[i], status);
            }
        }
    }

    private void updateU16Attributes(double[] values, AttributeStatus status) {
        // 根据O3设备协议，某些电压值需要除以10进行单位转换
        updateModbusShortAttribute("device_address", values[0], status);
        updateModbusShortAttribute("device_status", values[1], status);
        updateModbusShortAttribute("uv_amplification", values[2], status);
        updateAttribute("sample_temp_volt", values[3] / 10.0, status); // 除以10转换为mV
        updateAttribute("sample_press_volt", values[4] / 10.0, status); // 除以10转换为mV
        updateAttribute("pump_press_volt", values[5] / 10.0, status); // 除以10转换为mV
        updateAttribute("case_temp_volt", values[6] / 10.0, status); // 除以10转换为mV
        updateAttribute("case_temp", values[7] / 10.0, status); // 除以10转换为℃
        updateAttribute("voltage_12v", values[8], status);
        updateAttribute("voltage_15v", values[9], status);
        updateAttribute("voltage_5v", values[10], status);
        updateAttribute("voltage_3v3", values[11], status);
        updateModbusShortAttribute("measure_ref_valve_status", values[12], status);
        updateModbusShortAttribute("sample_cal_valve_status", values[13], status);
        updateModbusShortAttribute("builtin_pump_status", values[14], status);
        updateModbusShortAttribute("case_fan_status", values[15], status);
        updateAlarmInfo(values[16], SmsAlarmMessages.O3_ACTIVE, status);
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

    private void updateAttribute(String attrName, double value, AttributeStatus status) {
        AttributeAbility<?> attr = getAttrs().get(attrName);
        if (attr instanceof NumericAttribute) {
            NumericAttribute numAttr = (NumericAttribute) attr;
            numAttr.updateValue(value, status);
        }
    }

    private void updateModbusShortAttribute(String attrName, double registerValue, AttributeStatus status) {
        AttributeAbility<?> attr = getAttrs().get(attrName);
        if (attr instanceof ModbusShortAttribute) {
            ModbusShortAttribute shortAttr = (ModbusShortAttribute) attr;
            shortAttr.updateValue((short) ((int) registerValue & 0xFFFF), status);
        } else {
            updateAttribute(attrName, registerValue, status);
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
        DataSegment(int startAddress, int count, String description) {
            this.startAddress = startAddress;
            this.count = count;
        }
    }

    private static class SegmentData {
        final double[] values;    // 数值数组
        final short[] rawRegisters; // float 段原始寄存器（可选）

        SegmentData(String segmentName, double[] values) {
            this(segmentName, values, null);
        }

        SegmentData(String segmentName, double[] values, short[] rawRegisters) {
            this.values = values;
            this.rawRegisters = rawRegisters;
        }
    }
} 
