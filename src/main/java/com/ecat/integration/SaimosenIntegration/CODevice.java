package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.State.AttributeAbility;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.Unit.*;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTransactionStrategy;
import com.ecat.integration.ModbusIntegration.Tools;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SMS 8500 CO自动分析仪 - Saimosen
 * 基于022-通讯协议-CO2025_0819实现
 * 支持校准状态读取和设置功能
 *
 * @version 1.0.0
 * @author caohongbo
 */
public class CODevice extends SmsDeviceBase {

    // 数据段配置
    private static final Map<String, DataSegment> SEGMENT_CONFIG = new HashMap<>();
    static {
        SEGMENT_CONFIG.put("float_params", new DataSegment(0, 40, "float参数")); // 20个float参数，40个寄存器
        SEGMENT_CONFIG.put("u16_params", new DataSegment(60, 13, "U16参数")); // 13个U16参数，13个寄存器
        // 校准通讯协议 - 1000~1006地址段
        SEGMENT_CONFIG.put("zero_calibration_start", new DataSegment(0x3E8, 1, "零点校准开始"));  // 1000 - 只写
        SEGMENT_CONFIG.put("zero_calibration_confirm", new DataSegment(0x3E9, 1, "零点校准确认"));  // 1001 - 只写
        SEGMENT_CONFIG.put("zero_calibration_cancel", new DataSegment(0x3EA, 1, "零点校准取消"));  // 1002 - 只写
        SEGMENT_CONFIG.put("span_calibration_start", new DataSegment(0x3EB, 1, "跨度校准开始"));   // 1003 - 可读可写
        SEGMENT_CONFIG.put("span_calibration_confirm", new DataSegment(0x3EC, 1, "跨度校准确认"));  // 1004 - 只写
        SEGMENT_CONFIG.put("span_calibration_cancel", new DataSegment(0x3ED, 1, "跨度校准取消"));  // 1005 - 只写
        SEGMENT_CONFIG.put("calibration_status", new DataSegment(0x3EE, 1, "校准状态"));  // 1006 - 可读
    }

    private DeviceStatus deviceStatus = DeviceStatus.UNKNOWN;

    public CODevice(Map<String, Object> config) {
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
                            log.warn("CODevice " + getId() + " - Float data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });

            CompletableFuture<SegmentData> u16DataFuture = readSegment(source, "u16_params")
                    .thenApply(this::parseU16Data)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("CODevice " + getId() + " - U16 data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });

            // 读取可读的校准参数
            CompletableFuture<SegmentData> spanCalibConcentrationFuture = readSegment(source, "span_calibration_start")
                    .thenApply(this::parseSpanCalibrationConcentration)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("CODevice " + getId() + " - Span calibration data segment failed: " + throwable.getMessage());
                            return null; // 返回null表示失败
                        }
                        return result;
                    });

            CompletableFuture<SegmentData> instrumentCalibStatusFuture = readSegment(source, "calibration_status")
                    .thenApply(this::parseInstrumentCalibrationStatus)
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("CODevice " + getId() + " - Calibration status data segment failed: " + throwable.getMessage());
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
                                log.info("CODevice " + getId() + " - All segments updated successfully, device status: " + deviceStatus.getStatusName());
                            } else {
                                log.warn("CODevice " + getId() + " - Partial success: " + successCount + "/" + totalCount + " segments updated, device status: " + deviceStatus.getStatusName());
                            }
                            
                            // 只要有任何一个数据段成功，就返回true
                            return successCount > 0;
                            
                        } catch (Exception e) {
                            log.error("CODevice data processing failed: " + e.getMessage());
                            setAllAttributesStatus(AttributeStatus.MALFUNCTION);
                            return false;
                        }
                    });
        }).exceptionally(throwable -> {
            log.error("CODevice communication failed: " + throwable.getMessage());
            setAllAttributesStatus(AttributeStatus.MALFUNCTION);
            return false;
        });
    }

    /**
     * 读取单个数据段
     * 
     * @param source Modbus源
     * @param segmentName 段名称
     * @return 原始数据
     */
    private CompletableFuture<short[]> readSegment(ModbusSource source, String segmentName) {
        DataSegment segment = SEGMENT_CONFIG.get(segmentName);
        return source.readHoldingRegisters(segment.startAddress, segment.count)
                .thenApply(response -> {
                    short[] data = response.getShortData();
                    if (data == null) {
                        log.warn("CODevice " + getId() + " - " + segment.description + " data is null, using default values");
                        data = new short[segment.count * 2]; // 为float数据预留足够空间
                    }
                    log.info("CODevice " + getId() + " - " + segment.description + " received: " + java.util.Arrays.toString(data));
                    return data;
                });
    }

    public SegmentData parseFloatDataforTest(short[] rawData) {
        return parseFloatData(rawData);
    }
    
    public SegmentData parseU16DataforTest(short[] rawData) {
        return parseU16Data(rawData);
    }

    /**
     * 解析float数据
     * 
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseFloatData(short[] rawData) {
        double[] values = new double[rawData.length / 2];  // 每个float参数占用2个寄存器
        for (int i = 0; i < values.length; i++) {
            values[i] = Tools.convertLittleEndianByteSwapToFloat(rawData[i*2+1], rawData[i*2]);
        }
        return new SegmentData("float_params", values);
    }

    /**
     * 解析U16数据
     * 
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseU16Data(short[] rawData) {
        double[] values = new double[rawData.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = rawData[i] & 0xFFFF;
        }
        return new SegmentData("u16_params", values);
    }

    /**
     * 解析跨度校准浓度数据
     * 
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseSpanCalibrationConcentration(short[] rawData) {
        double[] values = new double[1];
        values[0] = rawData.length > 0 ? rawData[0] : 0.0;
        return new SegmentData("calibration_concentration", values);
    }

    /**
     * 解析仪器校准状态数据
     * 
     * @param rawData 原始数据
     * @return 解析后的段数据
     */
    private SegmentData parseInstrumentCalibrationStatus(short[] rawData) {
        double[] values = new double[1];
        values[0] = rawData.length > 0 ? rawData[0] : 0;
        return new SegmentData("calibration_status", values);
    }

    /**
     * 处理校准状态
     * 
     * @param calibData 校准数据
     */
    private void processCalibrationStatus(SegmentData calibData) {
        short calibrationStatus = (short) calibData.values[0];
        deviceStatus = parseDeviceStatus(calibrationStatus);
    }

    /**
     * 更新所有属性
     * 
     * @param floatData float数据
     * @param u16Data U16数据
     * @param spanCalibConcentration 跨度校准浓度数据
     * @param instrumentCalibStatus 仪器校准状态数据
     */
    private void updateAllAttributes(SegmentData floatData, SegmentData u16Data, 
                                   SegmentData spanCalibConcentration, SegmentData instrumentCalibStatus) {
        AttributeStatus baseStatus = mapToAttributeStatus(deviceStatus);
                                    
        // 更新float参数（如果数据可用）
        if (floatData != null) {
            updateFloatAttributes(floatData.values, baseStatus);
        } else {
            // 如果float数据不可用，设置相关属性为故障状态
            setFloatAttributesStatus(AttributeStatus.MALFUNCTION);
        }
        
        // 更新U16参数（如果数据可用）
        if (u16Data != null) {
            updateU16Attributes(u16Data.values, baseStatus);
        } else {
            // 如果U16数据不可用，设置相关属性为故障状态
            setU16AttributesStatus(AttributeStatus.MALFUNCTION);
        }
        
        // 更新校准参数（如果数据可用）
        if (spanCalibConcentration != null || instrumentCalibStatus != null) {
            updateCalibrationAttributes(spanCalibConcentration, instrumentCalibStatus, baseStatus);
        } else {
            // 如果校准数据不可用，设置相关属性为故障状态
            setCalibrationAttributesStatus(AttributeStatus.MALFUNCTION);
        }
        
        // 设置所有属性状态
        setAllAttributesStatus(baseStatus);
    }

    /**
     * 更新float属性
     * 
     * @param values 数值数组
     * @param status 属性状态
     */
    private void updateFloatAttributes(double[] values, AttributeStatus status) {
        updateAttribute("co", values[0], status);
        updateAttribute("measure_volt", values[1], status);
        updateAttribute("ref_volt", values[2], status);
        updateAttribute("measure_dark_current", values[3], status);
        updateAttribute("ref_dark_current", values[4], status);
        updateAttribute("slope", values[5], status);
        updateAttribute("intercept", values[6], status);
        updateAttribute("sample_press", values[7], status);
        updateAttribute("pump_press", values[8], status);
        updateAttribute("sample_flow", values[9], status);
        updateAttribute("negative_temp_coefficient", values[10], status);
        updateAttribute("correlation_wheel_temp", values[11], status);
        updateAttribute("scrubber_temp", values[12], status);
        updateAttribute("negative_temp_coefficient_corr", values[13], status);
        updateAttribute("correlation_wheel_temp_corr", values[14], status);
        updateAttribute("scrubber_temp_corr", values[15], status);
        updateAttribute("sample_press_corr", values[16], status);
        updateAttribute("pump_press_corr", values[17], status);
        updateAttribute("sample_flow_corr", values[18], status);
        updateAttribute("host_calc_measure_ref", values[19], status);
    }

    /**
     * 更新U16属性
     * 
     * @param values 数值数组
     * @param status 属性状态
     */
    private void updateU16Attributes(double[] values, AttributeStatus status) {
        updateAttribute("voltage_12v", values[0], status);
        updateAttribute("voltage_15v", values[1], status);
        updateAttribute("voltage_5v", values[2], status);
        updateAttribute("voltage_3v3", values[3], status);
        updateAttribute("optical_chamber_relay_status", values[4], status);
        updateAttribute("scrubber_relay_status", values[5], status);
        updateAttribute("correlation_wheel_relay_status", values[6], status);
        updateAttribute("sample_cal_relay_status", values[7], status);
        updateAttribute("auto_zero_value_relay_status", values[8], status);
        updateAttribute("start_dark_current_test", values[9], status);
        updateAttribute("start_dark_current_param_storage", values[10], status);
        updateAttribute("fault_code1", values[11], status);
        updateAttribute("fault_code2", values[12], status);
    }

    /**
     * 更新校准属性
     * 
     * @param spanCalibConcentration 跨度校准浓度数据
     * @param instrumentCalibStatus 仪器校准状态数据
     * @param status 属性状态
     */
    private void updateCalibrationAttributes(SegmentData spanCalibConcentration, 
                                           SegmentData instrumentCalibStatus, AttributeStatus status) {
        // 更新仪器校准状态（可读）
        updateAttribute("calibration_status", instrumentCalibStatus.values[0], status);

        // 更新跨度校准浓度（可读可写）
        // updateAttribute("calibration_concentration", spanCalibConcentration.values[0], status);
        // 根据设备状态设置校准浓度值
        double calibrationValue = getCalibrationValue(deviceStatus, spanCalibConcentration.values[0]);
        updateAttribute("calibration_concentration", calibrationValue, status);
    }

    /**
     * 设置所有属性状态
     * 
     * @param status 属性状态
     */
    private void setAllAttributesStatus(AttributeStatus status) {
        getAttrs().values().forEach(attr -> attr.setStatus(status));
        publicAttrsState();
    }
    
    /**
     * 设置float类型属性的状态
     * @param status 属性状态
     */
    private void setFloatAttributesStatus(AttributeStatus status) {
        // CO设备的float属性
        String[] floatAttrNames = {"co", "measure_volt", "ref_volt", "measure_dark_current", "ref_dark_current", "sample_press", "sample_temp", "sample_flow", "pump_press", "chamber_press", "o3_flow", "co_slope", "co_intercept", "sample_press_corr", "pump_press_corr", "chamber_press_corr", "sample_temp_corr", "sample_flow_corr", "mo_furnace_temp_corr", "mo_furnace_temp_setting"};
        
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
        // CO设备的U16属性
        String[] u16AttrNames = {"device_address", "device_status", "pmt_high_volt_setting", "sample_temp_volt", "sample_press_volt", "pump_press_volt", "chamber_temp_volt", "chamber_temp", "voltage_12v", "voltage_15v", "voltage_5v", "voltage_3v3", "optical_chamber_relay_status", "scrubber_relay_status", "correlation_wheel_relay_status", "sample_cal_relay_status", "auto_zero_value_relay_status", "start_dark_current_test", "start_dark_current_param_storage", "fault_code1", "fault_code2"};
        
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
     * 
     * @param statusRegister 寄存器值
     * @return 设备状态枚举
     */
    private DeviceStatus parseDeviceStatus(short statusRegister) {
        switch (statusRegister) {
            case 0: return DeviceStatus.MEASURE;           // 正常测量模式
            case 1: return DeviceStatus.ZERO_CALIBRATION;  // 零点校准模式
            case 2: return DeviceStatus.SPAN_CALIBRATION;  // 跨度校准模式
            default: return DeviceStatus.UNKNOWN;
        }
    }

    /**
     * 将设备状态映射为属性状态
     * 
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
     * 
     * @param deviceStatus 设备状态
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

    /**
     * 更新属性值
     * 
     * @param attrName 属性名称
     * @param value 属性值
     * @param status 属性状态
     */
    private void updateAttribute(String attrName, double value, AttributeStatus status) {
        NumericAttribute attr = (NumericAttribute) getAttrs().get(attrName);
        if (attr != null) {
            attr.updateValue(value, status);
        }
    }

    /**
     * 开始零点校准
     * 
     * @param concentration 零点校准浓度（通常为0）
     * @return 操作结果
     */
    public CompletableFuture<Boolean> startZeroCalibration(double concentration) {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 1. 设置校准模式为零点校准
            CompletableFuture<?> setModeFuture = source.writeRegister(0x3E8, 1);
            
            // 2. 设置零点校准浓度
            CompletableFuture<?> setConcentrationFuture = source.writeRegister(0x3E9, (int) concentration);
            
            // 3. 发送校准命令
            CompletableFuture<?> sendCommandFuture = source.writeRegister(0x3EA, 1);
            
            return CompletableFuture.allOf(setModeFuture, setConcentrationFuture, sendCommandFuture)
                    .thenApply(v -> {
                        log.info("CODevice " + getId() + " - Zero calibration started with concentration: " + concentration);
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("CODevice " + getId() + " - Failed to start zero calibration: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 开始跨度校准
     * 
     * @param concentration 跨度校准浓度（通常为400ppm）
     * @return 操作结果
     */
    public CompletableFuture<Boolean> startSpanCalibration(double concentration) {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 1. 设置校准模式为跨度校准
            CompletableFuture<?> setModeFuture = source.writeRegister(0x3E8, 2);
            
            // 2. 设置跨度校准浓度
            CompletableFuture<?> setConcentrationFuture = source.writeRegister(0x3EB, (int) concentration);
            
            // 3. 发送校准命令
            CompletableFuture<?> sendCommandFuture = source.writeRegister(0x3EC, 1);
            
            return CompletableFuture.allOf(setModeFuture, setConcentrationFuture, sendCommandFuture)
                    .thenApply(v -> {
                        log.info("CODevice " + getId() + " - Span calibration started with concentration: " + concentration);
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("CODevice " + getId() + " - Failed to start span calibration: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 停止校准
     * 
     * @return 操作结果
     */
    public CompletableFuture<Boolean> stopCalibration() {
        return ModbusTransactionStrategy.executeWithLambda(modbusSource, source -> {
            // 设置校准模式为测量模式
            return source.writeRegister(0x3E8, 0)
                    .thenApply(v -> {
                        log.info("CODevice " + getId() + " - Calibration stopped, back to measurement mode");
                        return true;
                    });
        }).exceptionally(throwable -> {
            log.error("CODevice " + getId() + " - Failed to stop calibration: " + throwable.getMessage());
            return false;
        });
    }

    /**
     * 读取跨度校准浓度
     * 
     * @return 跨度校准浓度值
     */
    public CompletableFuture<Double> readSpanCalibrationConcentration() {
        return modbusSource.readHoldingRegisters(0x3EB, 1)
                .thenApply(response -> {
                    short[] data = response.getShortData();
                    if (data != null && data.length > 0) {
                        double concentration = data[0];
                        log.info("CODevice " + getId() + " - Span calibration concentration: " + concentration);
                        return concentration;
                    }
                    return 0.0;
                })
                .exceptionally(throwable -> {
                    log.error("CODevice " + getId() + " - Failed to read span calibration concentration: " + throwable.getMessage());
                    return 0.0;
                });
    }

    /**
     * 创建属性（包含所有基本属性和校准相关属性）
     */
    private void createAttributes() {
        // 第一组参数（float类型）- 20个参数，与updateFloatAttributes顺序一致
        setAttribute(new NumericAttribute(
                "co", AttributeClass.CO,
                AirVolumeUnit.PPM,
                AirVolumeUnit.PPM,
                1, false, false));
        setAttribute(new NumericAttribute(
                "measure_volt", AttributeClass.VOLTAGE,
                VoltageUnit.MILLIVOLT,
                VoltageUnit.MILLIVOLT,
                2, false, false));
        setAttribute(new NumericAttribute(
                "ref_volt", AttributeClass.VOLTAGE,
                VoltageUnit.MILLIVOLT,
                VoltageUnit.MILLIVOLT,
                2, false, false));
        setAttribute(new NumericAttribute(
                "measure_dark_current", AttributeClass.CURRENT,
                NoConversionUnit.of("mA"),
                NoConversionUnit.of("mA"),
                1, false, false));
        setAttribute(new NumericAttribute(
                "ref_dark_current", AttributeClass.CURRENT,
                NoConversionUnit.of("mA"),
                NoConversionUnit.of("mA"),
                1, false, false));
        setAttribute(new NumericAttribute(
                "slope", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                3, false, true));
        setAttribute(new NumericAttribute(
                "intercept", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                3, false, true));
        setAttribute(new NumericAttribute(
                "sample_press", AttributeClass.PRESSURE,
                PressureUnit.KPA,
                PressureUnit.KPA,
                2, false, false));
        setAttribute(new NumericAttribute(
                "pump_press", AttributeClass.PUMP_P,
                PressureUnit.KPA,
                PressureUnit.KPA,
                2, false, false));
        setAttribute(new NumericAttribute(
                "sample_flow", AttributeClass.FLOW,
                LiterFlowUnit.ML_PER_MINUTE,
                LiterFlowUnit.ML_PER_MINUTE,
                1, false, false));
        setAttribute(new NumericAttribute(
                "negative_temp_coefficient", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, false));
        setAttribute(new NumericAttribute(
                "correlation_wheel_temp", AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                1, false, false));
        setAttribute(new NumericAttribute(
                "scrubber_temp", AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                1, false, false));
        setAttribute(new NumericAttribute(
                "negative_temp_coefficient_corr", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "correlation_wheel_temp_corr", AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                1, false, true));
        setAttribute(new NumericAttribute(
                "scrubber_temp_corr", AttributeClass.TEMPERATURE,
                TemperatureUnit.CELSIUS,
                TemperatureUnit.CELSIUS,
                1, false, true));
        setAttribute(new NumericAttribute(
                "sample_press_corr", AttributeClass.PRESSURE,
                PressureUnit.KPA,
                PressureUnit.KPA,
                2, false, true));
        setAttribute(new NumericAttribute(
                "pump_press_corr", AttributeClass.PRESSURE,
                PressureUnit.KPA,
                PressureUnit.KPA,
                2, false, true));
        setAttribute(new NumericAttribute(
                "sample_flow_corr", AttributeClass.FLOW,
                LiterFlowUnit.ML_PER_MINUTE,
                LiterFlowUnit.ML_PER_MINUTE,
                1, false, true));
        setAttribute(new NumericAttribute(
                "host_calc_measure_ref", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, false));
        
        // 第二组参数（U16类型）- 13个参数，与updateU16Attributes顺序一致
        setAttribute(new NumericAttribute(
                "voltage_12v", AttributeClass.VOLTAGE,
                VoltageUnit.MILLIVOLT,
                VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "voltage_15v", AttributeClass.VOLTAGE,
                VoltageUnit.MILLIVOLT,
                VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "voltage_5v", AttributeClass.VOLTAGE,
                VoltageUnit.MILLIVOLT,
                VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "voltage_3v3", AttributeClass.VOLTAGE,
                VoltageUnit.MILLIVOLT,
                VoltageUnit.MILLIVOLT,
                1, false, false));
        setAttribute(new NumericAttribute(
                "optical_chamber_relay_status", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "scrubber_relay_status", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "correlation_wheel_relay_status", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "sample_cal_relay_status", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "auto_zero_value_relay_status", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "start_dark_current_test", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "start_dark_current_param_storage", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, true));
        setAttribute(new NumericAttribute(
                "fault_code1", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, false));
        setAttribute(new NumericAttribute(
                "fault_code2", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, false, false));

        // 校准相关属性
        setAttribute(new NumericAttribute(
                "calibration_concentration", AttributeClass.CO,
                AirVolumeUnit.PPM,
                AirVolumeUnit.PPM,
                3, true, true));
        setAttribute(new NumericAttribute(
                "calibration_status", AttributeClass.TEXT,
                NoConversionUnit.of(""),
                NoConversionUnit.of(""),
                1, true, true));

        // 校准命令属性
        GasDeviceCommandAttribute commandAttr = new GasDeviceCommandAttribute("gas_device_command", AttributeClass.DISPATCH_COMMAND, new GasDeviceCommandAttribute.COCommandConfigFactory());
        commandAttr.setModbusSource(modbusSource);
        commandAttr.addDependencyAttribute((NumericAttribute) getAttrs().get("calibration_concentration"));
        setAttribute(commandAttr);
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
