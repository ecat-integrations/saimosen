package com.ecat.integration.SaimosenIntegration;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.BinaryAttribute;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.core.State.TextAttribute;
import com.ecat.core.State.Unit.AirMassUnit;
import com.ecat.core.State.Unit.CurrentUnit;
import com.ecat.core.State.Unit.FrequencyUnit;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.core.State.Unit.PowerUnit;
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.core.State.Unit.RatioUnit;
import com.ecat.core.State.Unit.SpeedUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.core.State.UnitInfo;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Tools;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusFloatAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusScalableFloatSRAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusShortAttribute;
import com.ecat.integration.ModbusIntegration.EndianConverter.AbstractEndianConverter;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;

import lombok.Getter;
import lombok.Setter;

/**
 * SMS8910 数据采集与质控联动仪完整协议 V2（寄存器 0~273）。
 * <p>
 * 对照文档：SMS8910-YF-02-02201 V1.0（20260815），Modbus 从站地址 01，
 * 读功能码 0x03、写功能码 0x06。独立实现，不继承 {@link QCDevice}。
 * <p>
 * 233~273 为嵌入的智能稳压电源段，U/I/P 缩放与 {@link SmartPowerStabilizer} 一致：
 * U÷10、I÷100、P÷100（kW）。采集温度从 245 起连续，无保留空隙。
 *
 * @version V2.2
 */
public class QCV2Device extends SmsDeviceBase {

    private static final int FIRST_BLOCK_START = 0x00;
    private static final int FIRST_BLOCK_COUNT = 110;

    private static final int SECOND_BLOCK_START = 0x6E;
    private static final int SECOND_BLOCK_COUNT = 123;

    private static final int POWER_SUPPLY_BLOCK_START = 233;
    /** 233~273 共 41 个 U16（与独立稳压器块长度一致） */
    private static final int POWER_SUPPLY_BLOCK_COUNT = 41;

    private static final int FILM_SWITCH_TIME_REGS = 5;
    /** 选项 key；显示文案走 strings.json 的 devices.qcv2device.{attr}_options */
    private static final List<String> ALARM_SELECT_OPTIONS = Arrays.asList("normal", "alarm");

    private final BigEndianConverter bigConverter = AbstractEndianConverter.getBigEndianConverter();

    private final Map<Integer, AttributeInfo> attributeMap = new HashMap<>();
    /** 换膜通道年月日时分合成后的单个时间参数，不进入 attributeMap，避免拆成 5 个数字属性。 */
    private final List<FilmSwitchTimeSpec> filmSwitchTimes = new ArrayList<>();

    private ConfigDefinition configDefinition;
    private DeviceConfig deviceConfig;

    /**
     * 块二前节拍（毫秒，默认 1000）：设备性能要求——连续读寄存器块之间须留隙。
     * 包可见，供同包测试注入小值：单测只验证链路语义（三块全读 + finishReadCycle 发布），
     * 节拍本身不是被测对象，生产默认不变。
     */
    long secondBlockGapMs = 1000L;

    /**
     * 块三前节拍（毫秒，默认 800）：设备性能要求，同 {@link #secondBlockGapMs} 语义
     * （智能稳压电源块 233~273）。
     */
    long thirdBlockGapMs = 800L;

    public QCV2Device(ConfigEntry entry) {
        super(entry);
        this.configDefinition = getConfigDefinition();
        this.deviceConfig = parseConfig(entry.getData());
        initAttributeMap();
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        initConfigDerivedAttributeValues();
        // 5 秒周期轮询：调度注册/源锁/锁忙跳过/异常韧性/统一日志全部由 ModbusPolling SDK
        // 托管（F-23 A 家族形态，同 QCDevice#start；本类为 9629cde 独立实现，合入时对齐
        // ——core 已把设备 IO 轮询逐出业务池且 IO 禁入，旧 scheduleWithFixedDelay 接线
        // 无编译出路）。两步构建：round 链内块间 1s/800ms 节拍经 polling.delay(ms) 表达
        final ModbusPolling polling = ModbusPolling.on(this, modbusSource);
        polling.round(source -> readRegisters(polling, source))
                .every(5, TimeUnit.SECONDS)
                .start();
    }

    @Override
    public void stop() {
        // 轮询生命周期已由 ModbusPolling SDK 内绑 RemovalHost（设备移除 sweep）收尾
    }

    @Override
    public void release() {
        stop();
        super.release();
    }

    /**
     * 按 SMS8910 质控仪协议表注册 0~273 全部业务寄存器。
     * 52/59（空调故障代码，协议标明空）不建属性。
     * 13（采样管漏水）协议有，当前硬件反馈没使用，暂不建属性。
     */
    private void initAttributeMap() {
        // —— 仪器状态：协议 0x03/0x06 读/写，质控过程写 1~4 打开对应标气阀 ——
        putU16(0, "system_state", AttributeClass.SYSTEM_STATE, "仪器状态", null, true);

        putFloat(1, "bench_temp", AttributeClass.TEMPERATURE, "站房温度", TemperatureUnit.CELSIUS, false);
        putFloat(3, "bench_humidity", AttributeClass.HUMIDITY, "站房湿度", RatioUnit.PERCENT, false);
        putFloat(5, "sample_tube_temp", AttributeClass.TEMPERATURE, "采样管温度", TemperatureUnit.CELSIUS, false);
        putFloat(7, "sample_tube_humidity", AttributeClass.HUMIDITY, "采样管湿度", RatioUnit.PERCENT, false);
        putFloat(9, "sample_tube_flow", AttributeClass.FLOW, "采样管流速", SpeedUnit.METER_PER_SECOND, false);
        putFloat(11, "sample_tube_pressure", AttributeClass.PRESSURE, "采样管静压", PressureUnit.KPA, false);
        // 采样管漏水状态：协议寄存器 13（0=报警，1=正常），当前硬件未实际返回，暂不采集。硬件就绪后再行采集。
        // putAlarmSelect(13, "sample_tube_leak", AttributeClass.LEAK_STATUS, "采样管漏水状态");

        putFloat(14, "station_ua", AttributeClass.VOLTAGE, "站房A相电压", VoltageUnit.VOLT, false);
        putFloat(16, "station_ub", AttributeClass.VOLTAGE, "站房B相电压", VoltageUnit.VOLT, false);
        putFloat(18, "station_uc", AttributeClass.VOLTAGE, "站房C相电压", VoltageUnit.VOLT, false);
        putFloat(20, "station_ia", AttributeClass.CURRENT, "站房A相电流", CurrentUnit.AMPERE, false);
        putFloat(22, "station_ib", AttributeClass.CURRENT, "站房B相电流", CurrentUnit.AMPERE, false);
        putFloat(24, "station_ic", AttributeClass.CURRENT, "站房C相电流", CurrentUnit.AMPERE, false);
        putFloat(26, "station_pa", AttributeClass.POWER, "A相有功功率", PowerUnit.WATT, false);
        putFloat(28, "station_pb", AttributeClass.POWER, "B相有功功率", PowerUnit.WATT, false);
        putFloat(30, "station_pc", AttributeClass.POWER, "C相有功功率", PowerUnit.WATT, false);
        putFloat(32, "station_qa", AttributeClass.REACTIVE_POWER, "A相无功功率", PowerUnit.WATT, false);
        putFloat(34, "station_qb", AttributeClass.REACTIVE_POWER, "B相无功功率", PowerUnit.WATT, false);
        putFloat(36, "station_qc", AttributeClass.REACTIVE_POWER, "C相无功功率", PowerUnit.WATT, false);
        putFloat(38, "station_pf_a", AttributeClass.POWER_FACTOR, "A相功率因数", null, false);
        putFloat(40, "station_pf_b", AttributeClass.POWER_FACTOR, "B相功率因数", null, false);
        putFloat(42, "station_pf_c", AttributeClass.POWER_FACTOR, "C相功率因数", null, false);
        putFloat(44, "voltage_freq", AttributeClass.FREQUENCY, "电压频率", FrequencyUnit.HERTZ, false);

        putU16(46, "ac1_power", AttributeClass.POWER_STATUS, "空调1开机状态", null, true);
        putU16(47, "ac1_direction", AttributeClass.DIRECTION, "空调1风向", null, false);
        putU16(48, "ac1_set_temp", AttributeClass.TEMPERATURE, "空调1设定温度", TemperatureUnit.CELSIUS, true);
        putU16(49, "ac1_mode", AttributeClass.MODE, "空调1运行模式", null, true);
        putU16(50, "ac1_speed", AttributeClass.WINDSPEED, "空调1风速", null, true);
        putU16(51, "ac1_cur_temp", AttributeClass.TEMPERATURE, "空调1当前温度", TemperatureUnit.CELSIUS, false);

        putU16(53, "ac2_power", AttributeClass.POWER_STATUS, "空调2开机状态", null, true);
        putU16(54, "ac2_direction", AttributeClass.DIRECTION, "空调2风向", null, false);
        putU16(55, "ac2_set_temp", AttributeClass.TEMPERATURE, "空调2设定温度", TemperatureUnit.CELSIUS, true);
        putU16(56, "ac2_mode", AttributeClass.MODE, "空调2运行模式", null, true);
        putU16(57, "ac2_speed", AttributeClass.WINDSPEED, "空调2风速", null, true);
        putU16(58, "ac2_cur_temp", AttributeClass.TEMPERATURE, "空调2当前温度", TemperatureUnit.CELSIUS, false);

        putFloat(60, "gas_cylinder1_pressure", AttributeClass.PRESSURE, "钢瓶气1压力", PressureUnit.KPA, false);
        putFloat(62, "gas_cylinder2_pressure", AttributeClass.PRESSURE, "钢瓶气2压力", PressureUnit.KPA, false);
        putFloat(64, "gas_cylinder3_pressure", AttributeClass.PRESSURE, "钢瓶气3压力", PressureUnit.KPA, false);
        putU16(66, "gas_cylinder_alarm_limit", AttributeClass.ALARM_LIMIT, "钢瓶气报警限值", RatioUnit.PERCENT, true);
        putFloat(67, "zero_gas_pressure", AttributeClass.PRESSURE, "零气压力", PressureUnit.KPA, false);
        putU16(69, "zero_gas_alarm_limit", AttributeClass.ALARM_LIMIT, "零气报警限值", PressureUnit.KPA, true);
        putFloat(70, "co_purifier_temp", AttributeClass.TEMPERATURE, "CO涤除器温度", TemperatureUnit.CELSIUS, false);
        putU16(72, "co_cylinder_leak", AttributeClass.LEAK_STATUS, "CO钢瓶气泄露状态", null, false);

        putBinary(73, "fan_control", AttributeClass.CONTROL, "风机控制", true);
        putBinary(74, "zero_gas_relay", AttributeClass.CONTROL, "零气继电器", true);
        putU16(75, "calibrator_relay", AttributeClass.CONTROL, "校准仪继电器", null, true);
        putU16(76, "calibration_valve_so2", AttributeClass.CONTROL, "SO2校准阀控制", null, true);
        putU16(77, "calibration_valve_nox", AttributeClass.CONTROL, "NOx校准阀控制", null, true);
        putU16(78, "calibration_valve_o3", AttributeClass.CONTROL, "O3校准阀控制", null, true);
        putU16(79, "calibration_valve_co", AttributeClass.CONTROL, "CO校准阀控制", null, true);
        putBinary(80, "light_control", AttributeClass.CONTROL, "灯", true);

        putAlarmSelect(81, "infrared_status", AttributeClass.STATUS, "红外状态");
        putAlarmSelect(82, "smoke_detector1", AttributeClass.ALARM_STATUS, "烟感1状态");
        putAlarmSelect(83, "smoke_detector2", AttributeClass.ALARM_STATUS, "烟感2状态");
        putAlarmSelect(84, "temp_detector1", AttributeClass.ALARM_STATUS, "温感1状态");
        putAlarmSelect(85, "temp_detector2", AttributeClass.ALARM_STATUS, "温感2状态");
        putAlarmSelect(86, "water_leak_detector", AttributeClass.ALARM_STATUS, "水浸状态");
        putU16(87, "gas_cylinder_alarm_status", AttributeClass.ALARM_STATUS, "钢瓶气压力报警状态", null, false);
        putU16(88, "zero_gas_alarm_status", AttributeClass.ALARM_STATUS, "零气压力报警状态", null, false);

        putFloat(89, "ups_input_voltage", AttributeClass.VOLTAGE, "UPS输入电压", VoltageUnit.VOLT, false);
        putFloat(91, "ups_output_voltage", AttributeClass.VOLTAGE, "UPS输出电压", VoltageUnit.VOLT, false);
        putU16(93, "ups_load_percent", AttributeClass.PERCENTAGE, "UPS输出负载百分比", RatioUnit.PERCENT, false);
        putFloat(94, "ups_input_freq", AttributeClass.FREQUENCY, "UPS输入频率", FrequencyUnit.HERTZ, false);
        putFloat(96, "ups_battery_voltage", AttributeClass.VOLTAGE, "UPS电池单元电压", VoltageUnit.VOLT, false);
        putFloat(98, "ups_battery_temp", AttributeClass.TEMPERATURE, "UPS电池温度", TemperatureUnit.CELSIUS, false);
        putU16(100, "ups_status", AttributeClass.STATUS, "UPS状态", null, false);

        putU16(101, "pm2_5_concentration", AttributeClass.PM2_5, "PM2.5浓度", AirMassUnit.UGM3, false);
        putU16(102, "pm10_concentration", AttributeClass.PM10, "PM10浓度", AirMassUnit.UGM3, false);
        putFloat(103, "o3_concentration_qc", AttributeClass.O3, "O3浓度", AirMassUnit.UGM3, false);
        putU16(105, "co_concentration_qc", AttributeClass.CO, "CO浓度", AirMassUnit.UGM3, false);
        putFloat(106, "no2_concentration_qc", AttributeClass.NO2, "NO2浓度", AirMassUnit.UGM3, false);
        putFloat(108, "so2_concentration_qc", AttributeClass.SO2, "SO2浓度", AirMassUnit.UGM3, false);

        putU16(110, "sample_tube_addr", AttributeClass.ADDRESS, "采样管地址", null, true);
        putBinary(111, "sample_tube_sampling_status", AttributeClass.SAMPLING_STATUS, "采样管采样状态", true);
        putScaled(112, "heating_temp", AttributeClass.TEMPERATURE, "加热温度",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, true, 1);
        putScaled(113, "fan_power", AttributeClass.POWER, "风机功率",
                ModbusDataType.U16X10, PowerUnit.WATT, false, 1);
        putScaled(114, "heating_belt_power", AttributeClass.POWER, "加热带功率",
                ModbusDataType.U16X10, PowerUnit.WATT, false, 1);

        registerFilmChangers();

        // 支管温度：协议表拆成两个 U16 行且第二行参数名为空，与其它 2 寄存器温度（float）同布局
        putFloat(144, "so2_gas_temp", AttributeClass.TEMPERATURE, "SO2支管温度", TemperatureUnit.CELSIUS, false);
        putFloat(146, "nox_gas_temp", AttributeClass.TEMPERATURE, "NOx支管温度", TemperatureUnit.CELSIUS, false);
        putFloat(148, "co_gas_temp", AttributeClass.TEMPERATURE, "CO支管温度", TemperatureUnit.CELSIUS, false);
        putFloat(150, "o3_gas_temp", AttributeClass.TEMPERATURE, "O3支管温度", TemperatureUnit.CELSIUS, false);

        putFloat(223, "vibration", AttributeClass.VIBRATION, "震动", SpeedUnit.MILLIMETER_PER_SECOND, false);
        putFloat(225, "pm10_std_flow", AttributeClass.FLOW, "PM10标况流量", LiterFlowUnit.L_PER_MINUTE, false, 2);
        putFloat(227, "pm10_working_flow", AttributeClass.FLOW, "PM10工况流量", LiterFlowUnit.L_PER_MINUTE, false, 2);
        putFloat(229, "pm2_5_std_flow", AttributeClass.FLOW, "PM2.5标况流量", LiterFlowUnit.L_PER_MINUTE, false, 2);
        putFloat(231, "pm2_5_working_flow", AttributeClass.FLOW, "PM2.5工况流量", LiterFlowUnit.L_PER_MINUTE, false, 2);

        registerSmartPowerSupplyAttributes();
        registerConfigDerivedAttributes();
    }

    /**
     * 换膜器地址/状态，以及通道切换时间（年月日时分合成单个显示参数）。
     * NOx 通道 1~2 的年月日被协议表中的支管温度（144~151）占用，无法组成完整时间，不采集。
     */
    private void registerFilmChangers() {
        putU16(115, "so2_film_changer_addr", AttributeClass.ADDRESS, "SO2换膜器地址", null, true);
        putU16(116, "so2_film_changer_status", AttributeClass.STATUS, "SO2换膜器状态", null, true);
        putFilmSwitchTimes(117, "so2_film", "SO2换膜器", 1, 5);

        putU16(142, "nox_film_changer_addr", AttributeClass.ADDRESS, "NOx换膜器地址", null, true);
        putU16(143, "nox_film_changer_status", AttributeClass.STATUS, "NOx换膜器状态", null, true);
        putFilmSwitchTimes(154, "nox_film", "NOx换膜器", 3, 5);

        putU16(169, "co_film_changer_addr", AttributeClass.ADDRESS, "CO换膜器地址", null, true);
        putU16(170, "co_film_changer_status", AttributeClass.STATUS, "CO换膜器状态", null, true);
        putFilmSwitchTimes(171, "co_film", "CO换膜器", 1, 5);

        putU16(196, "o3_film_changer_addr", AttributeClass.ADDRESS, "O3换膜器地址", null, true);
        putU16(197, "o3_film_changer_status", AttributeClass.STATUS, "O3换膜器状态", null, true);
        putFilmSwitchTimes(198, "o3_film", "O3换膜器", 1, 5);
    }

    /**
     * 智能稳压电源段。U÷10、I÷100、P÷100 与 {@link SmartPowerStabilizer} 一致；
     * 温湿度/保护参数说明栏为 10 的按 ×10 解析。
     */
    private void registerSmartPowerSupplyAttributes() {
        putChannelAttrs(233, "voltage_l", AttributeClass.VOLTAGE, "U",
                ModbusDataType.U16X10, VoltageUnit.VOLT, false, 2);
        putChannelAttrs(237, "current_l", AttributeClass.CURRENT, "I",
                ModbusDataType.U16X100, CurrentUnit.AMPERE, false, 2);
        putChannelAttrs(241, "power_l", AttributeClass.POWER, "P",
                ModbusDataType.U16X100, PowerUnit.KILOWATT, false, 2);

        putScaled(245, "temperature", AttributeClass.TEMPERATURE, "采集温度值",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, false, 2);
        putScaled(246, "humidity", AttributeClass.HUMIDITY, "采集湿度值",
                ModbusDataType.U16X10, RatioUnit.PERCENT, false, 2);

        putBinaryChannel(247, "relay_l", AttributeClass.CONTROL, "继电器状态", true);
        putChannelAttrs(251, "temp_alarm_high_l", AttributeClass.TEMPERATURE, "温度异常上限",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, true, 2);
        putChannelAttrs(255, "temp_alarm_low_l", AttributeClass.TEMPERATURE, "温度异常下限",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, true, 2);
        putChannelAttrs(259, "startup_delay_l", AttributeClass.TIME, "开机启动延时",
                ModbusDataType.U16X1, NoConversionUnit.of("s", "s"), true, 0);
        putChannelAttrs(263, "temp_trip_high_l", AttributeClass.TEMPERATURE, "温度跳闸上限",
                ModbusDataType.U16X10, TemperatureUnit.CELSIUS, true, 2);
        putBinaryChannel(267, "over_temp_protection_l", AttributeClass.STATUS, "超温保护是否启动", true);

        putAlarmSelect(271, "temp_humidity_comm_status", AttributeClass.STATUS, "温湿度传感器通讯状态");
        putAlarmSelect(272, "electric_param_comm_status", AttributeClass.STATUS, "电参数模块通讯状态");
        putScaled(273, "device_address", AttributeClass.STATUS, "设备地址",
                ModbusDataType.U16X1, null, true, 0);
    }

    /**
     * 登记完整的 5 寄存器通道时间（年/月/日/时/分），合成后显示为单个参数。
     * 不写入 attributeMap，避免在物理页拆成五个无意义的数字。
     */
    private void putFilmSwitchTimes(int startAddr, String idPrefix, String namePrefix, int fromChannel, int toChannel) {
        int addr = startAddr;
        for (int ch = fromChannel; ch <= toChannel; ch++) {
            filmSwitchTimes.add(new FilmSwitchTimeSpec(
                    idPrefix + "_ch" + ch + "_switch_time",
                    namePrefix + "通道" + ch + "切换时间",
                    addr));
            addr += FILM_SWITCH_TIME_REGS;
        }
    }

    private void putChannelAttrs(int startAddr, String idPrefix, AttributeClass cls, String nameSuffix,
            ModbusDataType type, UnitInfo unit, boolean writable, int precision) {
        for (int i = 1; i <= 4; i++) {
            putScaled(startAddr + i - 1, idPrefix + i, cls, "第" + i + "路" + nameSuffix,
                    type, unit, writable, precision);
        }
    }

    private void putU16(int addr, String id, AttributeClass cls, String name, UnitInfo unit, boolean writable) {
        attributeMap.put(addr, new AttributeInfo(id, cls, name, ModbusDataType.U16, 1, unit, writable, 0));
    }

    private void putBinary(int addr, String id, AttributeClass cls, String name, boolean writable) {
        attributeMap.put(addr, new AttributeInfo(id, cls, name, ModbusDataType.BINARY, 1, null, writable, 0));
    }

    private void putBinaryChannel(int startAddr, String idPrefix, AttributeClass cls, String nameSuffix,
            boolean writable) {
        for (int i = 1; i <= 4; i++) {
            putBinary(startAddr + i - 1, idPrefix + i, cls, "第" + i + "路" + nameSuffix, writable);
        }
    }

    private void putAlarmSelect(int addr, String id, AttributeClass cls, String name) {
        attributeMap.put(addr, new AttributeInfo(id, cls, name, ModbusDataType.ALARM_SELECT, 1, null, false, 0));
    }

    private void putFloat(int addr, String id, AttributeClass cls, String name, UnitInfo unit, boolean writable) {
        putFloat(addr, id, cls, name, unit, writable, 1);
    }

    private void putFloat(int addr, String id, AttributeClass cls, String name, UnitInfo unit,
            boolean writable, int precision) {
        attributeMap.put(addr, new AttributeInfo(id, cls, name, ModbusDataType.FLOAT, 2, unit, writable, precision));
    }

    private void putScaled(int addr, String id, AttributeClass cls, String name, ModbusDataType type,
            UnitInfo unit, boolean writable, int precision) {
        attributeMap.put(addr, new AttributeInfo(id, cls, name, type, 1, unit, writable, precision));
    }

    private void registerConfigDerivedAttributes() {
        setAttribute(new NumericAttribute("tube_length", "采样管长度", AttributeClass.NUMERIC,
                NoConversionUnit.of("m", "米"), NoConversionUnit.of("m", "米"), 2, false, false));
        setAttribute(new NumericAttribute("tube_inner_diameter", "采样管内径", AttributeClass.NUMERIC,
                NoConversionUnit.of("m", "米"), NoConversionUnit.of("m", "米"), 3, false, false));
        setAttribute(new NumericAttribute("sampling_tube_residence_time", "采样管滞留时间", AttributeClass.TIME,
                NoConversionUnit.of("s", "秒"), NoConversionUnit.of("s", "秒"), 1, false, false));
    }

    private void initConfigDerivedAttributeValues() {
        NumericAttribute tubeLengthAttr = (NumericAttribute) getAttrs().get("tube_length");
        if (tubeLengthAttr != null) {
            tubeLengthAttr.updateValue(deviceConfig.getSamplingTubeLength(), AttributeStatus.NORMAL);
        }
        NumericAttribute tubeDiameterAttr = (NumericAttribute) getAttrs().get("tube_inner_diameter");
        if (tubeDiameterAttr != null) {
            tubeDiameterAttr.updateValue(deviceConfig.getSamplingTubeInnerDiameter(), AttributeStatus.NORMAL);
        }
    }

    private void createAttributes() {
        for (Map.Entry<Integer, AttributeInfo> entry : attributeMap.entrySet()) {
            createAttribute(entry.getValue(), entry.getKey());
        }
        for (FilmSwitchTimeSpec spec : filmSwitchTimes) {
            setAttribute(new TextAttribute(spec.attributeId, spec.displayName,
                    AttributeClass.TIME, null, null, false));
        }
    }

    private void createAttribute(AttributeInfo info, Integer address) {
        switch (info.dataType) {
            case FLOAT:
                setAttribute(new ModbusFloatAttribute(
                        info.attributeId, info.displayName, info.attrClass,
                        info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                        modbusSource, address.shortValue(), bigConverter));
                break;
            case U16X10:
                setAttribute(new ModbusScalableFloatSRAttribute(
                        info.attributeId, info.displayName, info.attrClass,
                        info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                        modbusSource, address.shortValue(), bigConverter, 10.0f));
                break;
            case U16X100:
                setAttribute(new ModbusScalableFloatSRAttribute(
                        info.attributeId, info.displayName, info.attrClass,
                        info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                        modbusSource, address.shortValue(), bigConverter, 100.0f));
                break;
            case U16X1:
                setAttribute(new ModbusScalableFloatSRAttribute(
                        info.attributeId, info.displayName, info.attrClass,
                        info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                        modbusSource, address.shortValue(), bigConverter, 1.0f));
                break;
            case U16:
                setAttribute(new ModbusShortAttribute(
                        info.attributeId, info.displayName, info.attrClass,
                        info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                        modbusSource, address.shortValue()));
                break;
            case BINARY:
                setAttribute(new ModbusHoldingBinaryAttribute(
                        info.attributeId, info.displayName, info.attrClass,
                        info.isWritable, modbusSource, address));
                break;
            case ALARM_SELECT:
                setAttribute(new StringSelectAttribute(
                        info.attributeId, info.displayName, info.attrClass,
                        false, ALARM_SELECT_OPTIONS));
                break;
            default:
                throw new IllegalStateException("Unsupported Modbus data type: " + info.dataType);
        }
    }

    /**
     * 定时读取Modbus寄存器数据（SDK 单事务 round：三块读在同源锁内 FIFO 串行，
     * 块间 1s/800ms 节拍保留——设备性能要求。块一失败不阻断后续块——合入版原为
     * fire-and-forget 独立事务，异常被丢弃）
     */
    protected CompletableFuture<Boolean> readRegisters(ModbusPolling polling, ModbusSource source) {
        // 第一块：0~109
        return source.readHoldingRegisters(FIRST_BLOCK_START, FIRST_BLOCK_COUNT)
                .thenApply(firstResponse -> {
                    try {
                        short[] firstBlockRegisters = firstResponse.getShortData();
                        log.debug("QCV2Device 第一块数据: {} 长度: {}",
                                Arrays.toString(firstBlockRegisters), firstBlockRegisters.length);
                        parseBlockData(firstBlockRegisters, FIRST_BLOCK_START);
                        return true;
                    } catch (Exception e) {
                        log.error("QCV2Device 第一块数据解析失败: {}", e.getMessage());
                        getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                        publicAttrsState();
                        return false;
                    }
                })
                .handle((ok, ex) -> {
                    if (ex != null) {
                        log.error("QCV2Device 第一块数据读取失败，继续后续块: " + ex.getMessage());
                    }
                    return true;
                })
                // 块间 1s 节拍后读第二块（110~232；secondBlockGapMs：设备性能要求默认 1000，测试可注入）
                .thenCompose(v -> polling.delay(secondBlockGapMs).thenCompose(z ->
                        source.readHoldingRegisters(SECOND_BLOCK_START, SECOND_BLOCK_COUNT)
                                .thenApply(secondResponse -> {
                                    try {
                                        short[] secondBlockRegisters = secondResponse.getShortData();
                                        log.debug("QCV2Device 第二块数据: {} 长度: {}",
                                                Arrays.toString(secondBlockRegisters), secondBlockRegisters.length);
                                        parseBlockData(secondBlockRegisters, SECOND_BLOCK_START);
                                        updateFilmSwitchTimes(secondBlockRegisters, SECOND_BLOCK_START);
                                        return true;
                                    } catch (Exception e) {
                                        log.error("QCV2Device 第二块数据解析失败: {}", e.getMessage());
                                        getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                                        publicAttrsState();
                                        return false;
                                    }
                                })))
                .thenCompose(ok -> {
                    if (!Boolean.TRUE.equals(ok)) {
                        return CompletableFuture.completedFuture(false);
                    }
                    // 块间 800ms 节拍后读第三块（智能稳压电源 233~273；thirdBlockGapMs 默认 800，测试可注入）
                    return polling.delay(thirdBlockGapMs).thenCompose(z2 ->
                            source.readHoldingRegisters(POWER_SUPPLY_BLOCK_START, POWER_SUPPLY_BLOCK_COUNT)
                                    .thenApply(thirdResponse -> {
                                        try {
                                            short[] thirdBlockRegisters = thirdResponse.getShortData();
                                            log.debug("QCV2Device 第三块数据: {} 长度: {}",
                                                    Arrays.toString(thirdBlockRegisters), thirdBlockRegisters.length);
                                            parseBlockData(thirdBlockRegisters, POWER_SUPPLY_BLOCK_START);
                                            finishReadCycle();
                                            return true;
                                        } catch (Exception e) {
                                            log.error("QCV2Device 第三块数据解析失败: {}", e.getMessage());
                                            getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                                            publicAttrsState();
                                            return false;
                                        }
                                    }));
                });
    }

    private void finishReadCycle() {
        updateCalculateAttr();
        getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.NORMAL));
        publicAttrsState();
    }

    /**
     * 将通道年/月/日/时/分五个 U16 合成 "yyyy-MM-dd HH:mm"。无效或未写入的时间显示为空。
     */
    private void updateFilmSwitchTimes(short[] registers, int startAddress) {
        for (FilmSwitchTimeSpec spec : filmSwitchTimes) {
            int offset = spec.yearAddr - startAddress;
            if (offset < 0 || offset + FILM_SWITCH_TIME_REGS > registers.length) {
                continue;
            }
            String text = formatFilmSwitchTime(
                    registers[offset],
                    registers[offset + 1],
                    registers[offset + 2],
                    registers[offset + 3],
                    registers[offset + 4]);
            updateTextAttribute(spec.attributeId, text, AttributeStatus.NORMAL);
        }
    }

    static String formatFilmSwitchTime(short yearReg, short monthReg, short dayReg,
            short hourReg, short minuteReg) {
        int year = yearReg & 0xFFFF;
        int month = monthReg & 0xFFFF;
        int day = dayReg & 0xFFFF;
        int hour = hourReg & 0xFFFF;
        int minute = minuteReg & 0xFFFF;
        if (year == 0) {
            return "";
        }
        if (year < 100) {
            year += 2000;
        }
        try {
            LocalDateTime.of(year, month, day, hour, minute);
        } catch (DateTimeException e) {
            return "";
        }
        return String.format("%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute);
    }

    private void parseBlockData(short[] registers, int startAddress) {
        AttributeStatus status = AttributeStatus.NORMAL;
        for (int i = 0; i < registers.length; i++) {
            int address = startAddress + i;
            AttributeInfo info = attributeMap.get(address);
            if (info == null) {
                continue;
            }
            switch (info.dataType) {
                case FLOAT:
                    if (i + 1 < registers.length) {
                        float value = Tools.convertBigEndianToFloat(registers[i], registers[i + 1]);
                        updateModbusFloatAttribute(info.attributeId, value, status);
                        i++;
                    }
                    break;
                case U16X10:
                case U16X100:
                case U16X1:
                    updateModbusScalableAttribute(info.attributeId, registers[i], status);
                    break;
                case U16:
                    updateModbusShortAttribute(info.attributeId, registers[i], status);
                    break;
                case BINARY:
                    updateBinaryAttribute(info.attributeId, registers[i], status);
                    break;
                case ALARM_SELECT:
                    updateAlarmSelectAttribute(info.attributeId, registers[i], status);
                    break;
                default:
                    break;
            }
        }
    }

    private void updateModbusFloatAttribute(String attributeId, float value, AttributeStatus status) {
        ModbusFloatAttribute attr = (ModbusFloatAttribute) getAttrs().get(attributeId);
        if (attr != null) {
            attr.updateValue(value, status);
        }
    }

    private void updateModbusShortAttribute(String attributeId, short value, AttributeStatus status) {
        ModbusShortAttribute attr = (ModbusShortAttribute) getAttrs().get(attributeId);
        if (attr != null) {
            attr.updateValue(value, status);
        }
    }

    private void updateBinaryAttribute(String attributeId, short registerValue, AttributeStatus status) {
        BinaryAttribute attr = (BinaryAttribute) getAttrs().get(attributeId);
        if (attr == null) {
            return;
        }
        if ((registerValue & 0xFFFF) != 0) {
            attr.turnOn(status);
        } else {
            attr.turnOff(status);
        }
    }

    private void updateAlarmSelectAttribute(String attributeId, short registerValue, AttributeStatus status) {
        StringSelectAttribute attr = (StringSelectAttribute) getAttrs().get(attributeId);
        if (attr == null) {
            return;
        }
        // 协议：0=报警/异常，1=正常（红外/烟感/温感/水浸，以及通讯状态）
        String option = (registerValue & 0xFFFF) == 1 ? "normal" : "alarm";
        attr.updateValue(option, status);
    }

    private void updateModbusScalableAttribute(String attributeId, short value, AttributeStatus status) {
        ModbusScalableFloatSRAttribute attr = (ModbusScalableFloatSRAttribute) getAttrs().get(attributeId);
        if (attr != null) {
            attr.updateValue(value, status);
        }
    }

    private void updateCalculateAttr() {
        ModbusFloatAttribute samplingTubeFlowAttr = (ModbusFloatAttribute) getAttrs().get("sample_tube_flow");
        if (samplingTubeFlowAttr != null) {
            Double residenceTime = 999.0;
            AttrState<?> samplingTubeFlowState = samplingTubeFlowAttr.getState();
            Float samplingTubeFlow = samplingTubeFlowState != null ? (Float) samplingTubeFlowState.getValue() : null;
            if (samplingTubeFlow == null || samplingTubeFlow <= 0) {
                log.warn("采样管流量为0或null，无效，无法计算滞留时间，设置为极大的默认值");
            } else {
                residenceTime = deviceConfig.getSamplingTubeLength() / samplingTubeFlow;
            }
            NumericAttribute timeAttr = (NumericAttribute) getAttrs().get("sampling_tube_residence_time");
            timeAttr.updateValue(residenceTime, AttributeStatus.NORMAL);
        }

        ModbusFloatAttribute pm10StdFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm10_std_flow");
        if (pm10StdFlowAttr != null && pm10StdFlowAttr.getState() != null) {
            pm10StdFlowAttr.updateValue((Float) pm10StdFlowAttr.getState().getValue() * 1.021f);
        }
        ModbusFloatAttribute pm10WorkingFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm10_working_flow");
        if (pm10WorkingFlowAttr != null && pm10WorkingFlowAttr.getState() != null) {
            pm10WorkingFlowAttr.updateValue((Float) pm10WorkingFlowAttr.getState().getValue() * 1.021f);
        }
        ModbusFloatAttribute pm25StdFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm2_5_std_flow");
        if (pm25StdFlowAttr != null && pm25StdFlowAttr.getState() != null) {
            pm25StdFlowAttr.updateValue((Float) pm25StdFlowAttr.getState().getValue() * 0.997f);
        }
        ModbusFloatAttribute pm25WorkingFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm2_5_working_flow");
        if (pm25WorkingFlowAttr != null && pm25WorkingFlowAttr.getState() != null) {
            pm25WorkingFlowAttr.updateValue((Float) pm25WorkingFlowAttr.getState().getValue() * 0.997f);
        }
    }

    private static final class FilmSwitchTimeSpec {
        final String attributeId;
        final String displayName;
        final int yearAddr;

        FilmSwitchTimeSpec(String attributeId, String displayName, int yearAddr) {
            this.attributeId = attributeId;
            this.displayName = displayName;
            this.yearAddr = yearAddr;
        }
    }

    private static class AttributeInfo {
        final String attributeId;
        final AttributeClass attrClass;
        final String displayName;
        final ModbusDataType dataType;
        @SuppressWarnings("unused")
        final int registerCount;
        final UnitInfo unitType;
        final boolean isWritable;
        final int displayPrecision;

        AttributeInfo(String attributeId, AttributeClass attrClass, String displayName,
                ModbusDataType dataType, int registerCount, UnitInfo unitType, boolean isWritable, int displayPrecision) {
            this.attributeId = attributeId;
            this.attrClass = attrClass;
            this.displayName = displayName;
            this.dataType = dataType;
            this.registerCount = registerCount;
            this.unitType = unitType;
            this.isWritable = isWritable;
            this.displayPrecision = displayPrecision;
        }
    }

    private enum ModbusDataType {
        U16,
        U16X1,
        U16X10,
        U16X100,
        FLOAT,
        BINARY,
        ALARM_SELECT
    }

    public ConfigDefinition getConfigDefinition() {
        if (configDefinition == null) {
            configDefinition = new ConfigDefinition();
            ConfigItemBuilder deviceConfigItems = new ConfigItemBuilder()
                    .add(new ConfigItem<>("sampling_tube_length", Double.class, true, null))
                    .add(new ConfigItem<>("sampling_tube_inner_diameter", Double.class, true, null));
            ConfigItemBuilder config = new ConfigItemBuilder()
                    .add(new ConfigItem<>("device_settings", Map.class, true, null)
                            .addNestedConfigItems(deviceConfigItems));
            configDefinition.define(config);
        }
        return configDefinition;
    }

    @SuppressWarnings("unchecked")
    private DeviceConfig parseConfig(Map<String, Object> config) {
        if (configDefinition.validateConfig(config)) {
            log.info("配置验证通过，开始解析配置");
        } else {
            log.error("配置验证失败，无法解析配置");
            throw new IllegalArgumentException("配置验证失败."
                    + configDefinition.getInvalidConfigItems().entrySet().stream()
                    .map(entry -> "配置项: " + entry.getKey().getKey() + ", 错误信息: " + entry.getValue())
                    .collect(Collectors.joining(", ")));
        }
        DeviceConfig parsed = new DeviceConfig();
        Map<String, Object> dsConfig = (Map<String, Object>) config.get("device_settings");
        parsed.setSamplingTubeLength((double) dsConfig.getOrDefault("sampling_tube_length", 4.5));
        parsed.setSamplingTubeInnerDiameter((double) dsConfig.getOrDefault("sampling_tube_inner_diameter", 0.03));
        return parsed;
    }

    @Getter
    @Setter
    public static class DeviceConfig {
        private Double samplingTubeLength;
        private Double samplingTubeInnerDiameter;
    }
}
