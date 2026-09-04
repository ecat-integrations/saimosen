package com.ecat.integration.SaimosenIntegration;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.Unit.AirMassUnit;
import com.ecat.core.State.Unit.AirVolumeUnit;
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
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Tools;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusFloatAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusShortAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusScalableFloatSRAttribute;
import com.ecat.integration.ModbusIntegration.EndianConverter.AbstractEndianConverter;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;

import lombok.Getter;
import lombok.Setter;

/**
 * 适用SMS 8910 质控仪，支持分两次读取Modbus参数及中文显示名称
 * 
 * @version V1.0
 * @author coffee
 */
public class QCDevice extends SmsDeviceBase {

    /** 轮询周期（毫秒）。生产=标准采集频率 5s；单测注入短周期压缩负向等待窗。 */
    protected long pollPeriodMs = 5_000L;

    // 连续读取地址段定义 - 分两次读取全部232个参数
    protected static final int FIRST_BLOCK_START = 0x00; // 第一块起始地址
    protected static final int FIRST_BLOCK_COUNT = 110; // 第一块读取110个寄存器
    
    protected static final int SECOND_BLOCK_START = 0x6E; // 第二块起始地址(0x00 + 110 = 0x6E)
    protected static final int SECOND_BLOCK_COUNT = 123; // 第二块读取123个寄存器（覆盖地址 110~232）
    
    // 转换器
    BigEndianConverter bigConverter = AbstractEndianConverter.getBigEndianConverter();

    protected Map<Integer, AttributeInfo> attributeMap = new HashMap<>();
    protected Map<Integer, List<AttributeInfo>> duplicateMap = new HashMap<>();

    /**
     * 块二前节拍（毫秒，默认 1000）：设备性能要求——连续读寄存器块之间须留隙，盲发
     * 第二笔会锁忙静默缺失（见 readRegisters 注释）。包可见，供同包测试注入小值：
     * 单测只验证链路语义（两块全读 + finishReadCycle 发布），节拍本身不是被测对象，
     * 生产默认不变。
     */
    long secondBlockGapMs = 1000L;

    private ConfigDefinition configDefinition;
    private DeviceConfig deviceConfig;


    public QCDevice(ConfigEntry entry) {
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
        // 配置派生属性在 id 解析后（start 时机）赋值，确保 state.deviceId 为持久化 id
        initConfigDerivedAttributeValues();

        // 周期轮询（pollPeriodMs，生产默认 5s）：调度注册/源锁/锁忙跳过/异常韧性/统一日志全部由 ModbusPolling SDK 托管。
        // 两步构建：round 链内块间 1s/500ms 节拍经 polling.delay(ms) 糖表达（收编本地
        // getScheduledExecutor delay() 助手；同 SMS8600V2Device 的 serial 侧迁移形态）
        final ModbusPolling polling = ModbusPolling.on(this, modbusSource);
        polling.round(source -> readRegisters(polling, source))
                .every(pollPeriodMs, TimeUnit.MILLISECONDS)
                .start();
    }

    @Override
    public void stop() {
        // 轮询生命周期已由 ModbusPolling SDK 内绑 RemovalHost（设备移除 sweep）收尾
    }

    @Override
    public void release() {
        super.release();
    }

    /**
     * 初始化属性映射表，对应参数表中的所有参数（包含中文显示名称）
     */
    protected void initAttributeMap() {
        // 仪器状态 - 地址0，可写
        attributeMap.put(0, new AttributeInfo("system_state", AttributeClass.SYSTEM_STATE, "仪器状态",
                ModbusDataType.U16, 1, null, false));
        
        // 站房温度 - 地址1/2，只读
        attributeMap.put(1, new AttributeInfo("bench_temp", AttributeClass.TEMPERATURE, "站房温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));
        
        // 站房湿度 - 地址3/4，只读
        attributeMap.put(3, new AttributeInfo("bench_humidity", AttributeClass.HUMIDITY, "站房湿度",
                ModbusDataType.FLOAT, 2, RatioUnit.PERCENT, false));
        
        // 采样管温度 - 地址5/6，只读
        attributeMap.put(5, new AttributeInfo("sample_tube_temp", AttributeClass.TEMPERATURE, "采样管温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));
        
        // 采样管湿度 - 地址7/8，只读
        attributeMap.put(7, new AttributeInfo("sample_tube_humidity", AttributeClass.HUMIDITY, "采样管湿度",
                ModbusDataType.FLOAT, 2, RatioUnit.PERCENT, false));
        
        // 采样管流速 - 地址9/10，只读
        attributeMap.put(9, new AttributeInfo("sample_tube_flow", AttributeClass.FLOW, "采样管流速",
                ModbusDataType.FLOAT, 2, SpeedUnit.METER_PER_SECOND, false));
        
        // 采样管静压 - 地址11/12，只读
        attributeMap.put(11, new AttributeInfo("sample_tube_pressure", AttributeClass.PRESSURE, "采样管静压",
                ModbusDataType.FLOAT, 2, PressureUnit.KPA, false));
        
        // 采样管漏水状态 - 地址13，可写
        attributeMap.put(13, new AttributeInfo("sample_tube_leak", AttributeClass.LEAK_STATUS, "采样管漏水状态",
                ModbusDataType.U16, 1, null, true));
        
        // 站房A相电压 - 地址14/15，只读
        attributeMap.put(14, new AttributeInfo("station_ua", AttributeClass.VOLTAGE, "站房A相电压",
                ModbusDataType.FLOAT, 2, VoltageUnit.VOLT, false));
        
        // 站房B相电压 - 地址16/17，只读
        attributeMap.put(16, new AttributeInfo("station_ub", AttributeClass.VOLTAGE, "站房B相电压",
                ModbusDataType.FLOAT, 2, VoltageUnit.VOLT, false));
        
        // 站房C相电压 - 地址18/19，只读
        attributeMap.put(18, new AttributeInfo("station_uc", AttributeClass.VOLTAGE, "站房C相电压",
                ModbusDataType.FLOAT, 2, VoltageUnit.VOLT, false));
        
        // 站房A相电流 - 地址20/21，只读
        attributeMap.put(20, new AttributeInfo("station_ia", AttributeClass.CURRENT, "站房A相电流",
                ModbusDataType.FLOAT, 2, CurrentUnit.AMPERE, false));
        
        // 站房B相电流 - 地址22/23，只读
        attributeMap.put(22, new AttributeInfo("station_ib", AttributeClass.CURRENT, "站房B相电流",
                ModbusDataType.FLOAT, 2, CurrentUnit.AMPERE, false));
        
        // 站房C相电流 - 地址24/25，只读
        attributeMap.put(24, new AttributeInfo("station_ic", AttributeClass.CURRENT, "站房C相电流",
                ModbusDataType.FLOAT, 2, CurrentUnit.AMPERE, false));
        
        // A相有功功率 - 地址26/27，只读
        attributeMap.put(26, new AttributeInfo("station_pa", AttributeClass.POWER, "A相有功功率",
                ModbusDataType.FLOAT, 2, PowerUnit.WATT, false));
        
        // B相有功功率 - 地址28/29，只读
        attributeMap.put(28, new AttributeInfo("station_pb", AttributeClass.POWER, "B相有功功率",
                ModbusDataType.FLOAT, 2, PowerUnit.WATT, false));
        
        // C相有功功率 - 地址30/31，只读
        attributeMap.put(30, new AttributeInfo("station_pc", AttributeClass.POWER, "C相有功功率",
                ModbusDataType.FLOAT, 2, PowerUnit.WATT, false));
        
        // A相无功功率 - 地址32/33，只读
        attributeMap.put(32, new AttributeInfo("station_qa", AttributeClass.REACTIVE_POWER, "A相无功功率",
                ModbusDataType.FLOAT, 2, PowerUnit.WATT, false));
        
        // B相无功功率 - 地址34/35，只读
        attributeMap.put(34, new AttributeInfo("station_qb", AttributeClass.REACTIVE_POWER, "B相无功功率",
                ModbusDataType.FLOAT, 2, PowerUnit.WATT, false));
        
        // C相无功功率 - 地址36/37，只读
        attributeMap.put(36, new AttributeInfo("station_qc", AttributeClass.REACTIVE_POWER, "C相无功功率",
                ModbusDataType.FLOAT, 2, PowerUnit.WATT, false));
        
        // A相功率因数 - 地址38/39，只读
        attributeMap.put(38, new AttributeInfo("station_pf_a", AttributeClass.POWER_FACTOR, "A相功率因数",
                ModbusDataType.FLOAT, 2, null, false));
        
        // B相功率因数 - 地址40/41，只读
        attributeMap.put(40, new AttributeInfo("station_pf_b", AttributeClass.POWER_FACTOR, "B相功率因数",
                ModbusDataType.FLOAT, 2, null, false));
        
        // C相功率因数 - 地址42/43，只读
        attributeMap.put(42, new AttributeInfo("station_pf_c", AttributeClass.POWER_FACTOR, "C相功率因数",
                ModbusDataType.FLOAT, 2, null, false));
        
        // 电压频率 - 地址44/45，只读
        attributeMap.put(44, new AttributeInfo("voltage_freq", AttributeClass.FREQUENCY, "电压频率",
                ModbusDataType.FLOAT, 2, null, false));
        
        // 空调1开机状态 - 地址46，可写
        attributeMap.put(46, new AttributeInfo("ac1_power", AttributeClass.POWER_STATUS, "空调1开机状态",
                ModbusDataType.U16, 1, null, true));
        
        // 空调1风向 - 地址47，只读
        attributeMap.put(47, new AttributeInfo("ac1_direction", AttributeClass.DIRECTION, "空调1风向",
                ModbusDataType.U16, 1, null, false));
        
        // 空调1设定温度 - 地址48，可写
        attributeMap.put(48, new AttributeInfo("ac1_set_temp", AttributeClass.TEMPERATURE, "空调1设定温度",
                ModbusDataType.U16, 1, TemperatureUnit.CELSIUS, true));
        
        // 空调1运行模式 - 地址49，可写
        attributeMap.put(49, new AttributeInfo("ac1_mode", AttributeClass.MODE, "空调1运行模式",
                ModbusDataType.U16, 1, null, true));
        
        // 空调1风速 - 地址50，可写
        attributeMap.put(50, new AttributeInfo("ac1_speed", AttributeClass.WINDSPEED, "空调1风速",
                ModbusDataType.U16, 1, null, true));
        
        // 空调1当前温度 - 地址51，只读
        attributeMap.put(51, new AttributeInfo("ac1_cur_temp", AttributeClass.TEMPERATURE, "空调1当前温度",
                ModbusDataType.U16, 1, TemperatureUnit.CELSIUS, false));
        
        // 空调2开机状态 - 地址53，可写
        attributeMap.put(53, new AttributeInfo("ac2_power", AttributeClass.POWER_STATUS, "空调2开机状态",
                ModbusDataType.U16, 1, null, true));
        
        // 空调2风向 - 地址54，只读
        attributeMap.put(54, new AttributeInfo("ac2_direction", AttributeClass.DIRECTION, "空调2风向",
                ModbusDataType.U16, 1, null, false));
        
        // 空调2设定温度 - 地址55，可写
        attributeMap.put(55, new AttributeInfo("ac2_set_temp", AttributeClass.TEMPERATURE, "空调2设定温度",
                ModbusDataType.U16, 1, TemperatureUnit.CELSIUS, true));
        
        // 空调2运行模式 - 地址56，可写
        attributeMap.put(56, new AttributeInfo("ac2_mode", AttributeClass.MODE, "空调2运行模式",
                ModbusDataType.U16, 1, null, true));
        
        // 空调2风速 - 地址57，可写
        attributeMap.put(57, new AttributeInfo("ac2_speed", AttributeClass.WINDSPEED, "空调2风速",
                ModbusDataType.U16, 1, null, true));
        
        // 空调2当前温度 - 地址58，只读
        attributeMap.put(58, new AttributeInfo("ac2_cur_temp", AttributeClass.TEMPERATURE, "空调2当前温度",
                ModbusDataType.U16, 1, TemperatureUnit.CELSIUS, false));
        
        // 钢瓶气1压力 - 地址60/61，只读 SO2气罐
        attributeMap.put(60, new AttributeInfo("gas_cylinder1_pressure", AttributeClass.PRESSURE, "钢瓶气1压力",
                ModbusDataType.FLOAT, 2, PressureUnit.KPA, false));
        
        // 钢瓶气2压力 - 地址62/63，只读 NOx气罐
        attributeMap.put(62, new AttributeInfo("gas_cylinder2_pressure", AttributeClass.PRESSURE, "钢瓶气2压力",
                ModbusDataType.FLOAT, 2, PressureUnit.KPA, false));
        
        // 钢瓶气3压力 - 地址64/65，只读 CO气罐
        attributeMap.put(64, new AttributeInfo("gas_cylinder3_pressure", AttributeClass.PRESSURE, "钢瓶气3压力",
                ModbusDataType.FLOAT, 2, PressureUnit.KPA, false));
        
        // 钢瓶气报警限值 - 地址66，可写
        attributeMap.put(66, new AttributeInfo("gas_cylinder_alarm_limit", AttributeClass.ALARM_LIMIT, "钢瓶气报警限值",
                ModbusDataType.U16, 1, null, true));
        
        // 零气压力 - 地址67/68，只读
        attributeMap.put(67, new AttributeInfo("zero_gas_pressure", AttributeClass.PRESSURE, "零气压力",
                ModbusDataType.FLOAT, 2, PressureUnit.KPA, false));
        
        // 零气报警限值 - 地址69，可写
        attributeMap.put(69, new AttributeInfo("zero_gas_alarm_limit", AttributeClass.ALARM_LIMIT, "零气报警限值",
                ModbusDataType.U16, 1, null, true));
        
        // CO涤除器温度 - 地址70/71，只读
        attributeMap.put(70, new AttributeInfo("co_purifier_temp", AttributeClass.TEMPERATURE, "CO涤除器温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));
        
        // CO钢瓶气泄露状态 - 地址72，只读
        attributeMap.put(72, new AttributeInfo("co_cylinder_leak", AttributeClass.LEAK_STATUS, "CO钢瓶气泄露状态",
                ModbusDataType.U16, 1, null, false));
        
        // 风机控制 - 地址73，可写
        attributeMap.put(73, new AttributeInfo("fan_control", AttributeClass.CONTROL, "风机控制",
                ModbusDataType.U16, 1, null, true));
        
        // 零气继电器 - 地址74，可写
        attributeMap.put(74, new AttributeInfo("zero_gas_relay", AttributeClass.CONTROL, "零气继电器",
                ModbusDataType.U16, 1, null, true));
        
        // 校准仪继电器 - 地址75，可写
        attributeMap.put(75, new AttributeInfo("calibrator_relay", AttributeClass.CONTROL, "校准仪继电器",
                ModbusDataType.U16, 1, null, true));
        
        // 校准阀1控制 - 地址76，可写
        attributeMap.put(76, new AttributeInfo("calibration_valve_so2", AttributeClass.CONTROL, "SO2校准阀控制",
                ModbusDataType.U16, 1, null, true));
        
        // 校准阀2控制 - 地址77，可写
        attributeMap.put(77, new AttributeInfo("calibration_valve_nox", AttributeClass.CONTROL, "NOx校准阀控制",
                ModbusDataType.U16, 1, null, true));
        
        // 校准阀3控制 - 地址78，可写
        attributeMap.put(78, new AttributeInfo("calibration_valve_o3", AttributeClass.CONTROL, "O3校准阀控制",
                ModbusDataType.U16, 1, null, true));
        
        // 校准阀4控制 - 地址79，可写
        attributeMap.put(79, new AttributeInfo("calibration_valve_co", AttributeClass.CONTROL, "CO校准阀控制",
                ModbusDataType.U16, 1, null, true));
        
        // 灯 - 地址80，可写
        attributeMap.put(80, new AttributeInfo("light_control", AttributeClass.CONTROL, "灯",
                ModbusDataType.U16, 1, null, true));
        
        // 红外状态 - 地址81，只读
        attributeMap.put(81, new AttributeInfo("infrared_status", AttributeClass.STATUS, "红外状态",
                ModbusDataType.U16, 1, null, false));
        
        // 烟感1状态 - 地址82，只读
        attributeMap.put(82, new AttributeInfo("smoke_detector1", AttributeClass.ALARM_STATUS, "烟感1状态",
                ModbusDataType.U16, 1, null, false));
        
        // 烟感2状态 - 地址83，只读
        attributeMap.put(83, new AttributeInfo("smoke_detector2", AttributeClass.ALARM_STATUS, "烟感2状态",
                ModbusDataType.U16, 1, null, false));
        
        // 温感1状态 - 地址84，只读
        attributeMap.put(84, new AttributeInfo("temp_detector1", AttributeClass.ALARM_STATUS, "温感1状态",
                ModbusDataType.U16, 1, null, false));
        
        // 温感2状态 - 地址85，只读
        attributeMap.put(85, new AttributeInfo("temp_detector2", AttributeClass.ALARM_STATUS, "温感2状态",
                ModbusDataType.U16, 1, null, false));
        
        // 水浸状态 - 地址86，只读
        attributeMap.put(86, new AttributeInfo("water_leak_detector", AttributeClass.ALARM_STATUS, "水浸状态",
                ModbusDataType.U16, 1, null, false));
        
        // 钢瓶气压力报警状态 - 地址87，只读
        attributeMap.put(87, new AttributeInfo("gas_cylinder_alarm_status", AttributeClass.ALARM_STATUS, "钢瓶气压力报警状态",
                ModbusDataType.U16, 1, null, false));
        
        // 零气压力报警状态 - 地址88，只读
        attributeMap.put(88, new AttributeInfo("zero_gas_alarm_status", AttributeClass.ALARM_STATUS, "零气压力报警状态",
                ModbusDataType.U16, 1, null, false));
        
        // UPS输入电压 - 地址89/90，只读
        attributeMap.put(89, new AttributeInfo("ups_input_voltage", AttributeClass.VOLTAGE, "UPS输入电压",
                ModbusDataType.FLOAT, 2, VoltageUnit.VOLT, false));
        
        // UPS输出电压 - 地址91/92，只读
        attributeMap.put(91, new AttributeInfo("ups_output_voltage", AttributeClass.VOLTAGE, "UPS输出电压",
                ModbusDataType.FLOAT, 2, VoltageUnit.VOLT, false));
        
        // UPS输出负载百分比 - 地址93，只读
        attributeMap.put(93, new AttributeInfo("ups_load_percent", AttributeClass.PERCENTAGE, "UPS输出负载百分比",
                ModbusDataType.U16, 1, null, false));
        
        // UPS输入频率 - 地址94/95，只读
        attributeMap.put(94, new AttributeInfo("ups_input_freq", AttributeClass.FREQUENCY, "UPS输入频率",
                ModbusDataType.FLOAT, 2, FrequencyUnit.HERTZ, false));
        
        // UPS电池单元电压 - 地址96/97，只读
        attributeMap.put(96, new AttributeInfo("ups_battery_voltage", AttributeClass.VOLTAGE, "UPS电池单元电压",
                ModbusDataType.FLOAT, 2, VoltageUnit.VOLT, false));
        
        // UPS电池温度 - 地址98/99，只读
        attributeMap.put(98, new AttributeInfo("ups_battery_temp", AttributeClass.TEMPERATURE, "UPS电池温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));
        
        // UPS状态 - 地址100，只读
        attributeMap.put(100, new AttributeInfo("ups_status", AttributeClass.STATUS, "UPS状态",
                ModbusDataType.U16, 1, null, false));
        
        // PM2.5浓度 - 地址101，只读
        attributeMap.put(101, new AttributeInfo("pm2_5_concentration", AttributeClass.PM2_5, "PM2.5浓度",
                ModbusDataType.U16, 1, AirMassUnit.UGM3, false));
        
        // PM10浓度 - 地址102，只读
        attributeMap.put(102, new AttributeInfo("pm10_concentration", AttributeClass.PM10, "PM10浓度",
                ModbusDataType.U16, 1, AirMassUnit.UGM3, false));
        
        // O3浓度 - 地址103/104，只读
        attributeMap.put(103, new AttributeInfo("o3_concentration_qc", AttributeClass.O3, "O3浓度",
                ModbusDataType.FLOAT, 2, AirVolumeUnit.PPM, false));
        
        // CO浓度 - 地址105，只读
        attributeMap.put(105, new AttributeInfo("co_concentration_qc", AttributeClass.CO, "CO浓度",
                ModbusDataType.U16, 1, AirVolumeUnit.PPM, false));
        
        // NO2浓度 - 地址106/107，只读
        attributeMap.put(106, new AttributeInfo("no2_concentration_qc", AttributeClass.NO2, "NO2浓度",
                ModbusDataType.FLOAT, 2, AirVolumeUnit.PPM, false));
        
        // SO2浓度 - 地址108/109，只读
        attributeMap.put(108, new AttributeInfo("so2_concentration_qc", AttributeClass.SO2, "SO2浓度",
                ModbusDataType.FLOAT, 2, AirVolumeUnit.PPM, false));
        
        // 采样管地址 - 地址110，可写
        attributeMap.put(110, new AttributeInfo("sample_tube_addr", AttributeClass.ADDRESS, "采样管地址",
                ModbusDataType.U16, 1, null, true));
        
        // 以下为第二块数据(111-232)
        // 采样管采样状态 - 地址111，可写
        attributeMap.put(111, new AttributeInfo("sample_tube_sampling_status", AttributeClass.SAMPLING_STATUS, "采样管采样状态",
                ModbusDataType.U16, 1, null, true));
        
        // 加热温度 - 地址112（U16X10类型）
        attributeMap.put(112, new AttributeInfo("heating_temp", AttributeClass.TEMPERATURE, "加热温度",
                ModbusDataType.U16X10, 1, TemperatureUnit.CELSIUS, true));
        
        // 风机功率 - 地址113（U16X10类型）
        attributeMap.put(113, new AttributeInfo("fan_power", AttributeClass.POWER, "风机功率",
                ModbusDataType.U16X10, 1, PowerUnit.WATT, true));
        
        // 加热带功率 - 地址114（U16X10类型）
        attributeMap.put(114, new AttributeInfo("heating_belt_power", AttributeClass.POWER, "加热带功率",
                ModbusDataType.U16X10, 1, PowerUnit.WATT, true));
        
        // SO2换膜器状态 - 地址116，只读
        attributeMap.put(116, new AttributeInfo("so2_film_changer_status", AttributeClass.STATUS, "SO2换膜器状态",
                ModbusDataType.U16, 1, null, true));
        
        // 重复的换膜器状态属性，使用列表存储
        List<AttributeInfo> filmChangerList = new ArrayList<>();
        filmChangerList.add(new AttributeInfo("nox_film_changer_status", AttributeClass.STATUS, "NOx换膜器状态",
                        ModbusDataType.U16, 1, null, true));
        filmChangerList.add(new AttributeInfo("co_film_changer_status", AttributeClass.STATUS, "CO换膜器状态",
                ModbusDataType.U16, 1, null, true));
        filmChangerList.add(new AttributeInfo("o3_film_changer_status", AttributeClass.STATUS, "O3换膜器状态",
                ModbusDataType.U16, 1, null, true));
        duplicateMap.put(116, filmChangerList);

        // NOx换膜器状态 - 地址143，可写（中间参数省略，结构同前）
        // attributeMap.put(116, new AttributeInfo("NOX_FILM_CHANGER_STATUS", AttributeClass.STATUS, "NOx换膜器状态", 
        //         ModbusDataType.U16, 1, null, true));
        
        // CO换膜器状态 - 地址170，可写
        // attributeMap.put(116, new AttributeInfo("CO_FILM_CHANGER_STATUS", AttributeClass.STATUS, "CO换膜器状态", 
        //         ModbusDataType.U16, 1, null, true));

        // O3换膜器状态 - 地址197，只读（中间参数省略，结构同前）
        // attributeMap.put(116, new AttributeInfo("O3_FILM_CHANGER_STATUS", AttributeClass.STATUS, "O3换膜器状态", 
        //         ModbusDataType.U16, 1, null, true));

        attributeMap.put(144, new AttributeInfo("so2_gas_temp", AttributeClass.TEMPERATURE, "SO2支管温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));

        attributeMap.put(146, new AttributeInfo("nox_gas_temp", AttributeClass.TEMPERATURE, "NOX支管温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));
        
        attributeMap.put(148, new AttributeInfo("co_gas_temp", AttributeClass.TEMPERATURE, "CO支管温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));
        
        attributeMap.put(150, new AttributeInfo("o3_gas_temp", AttributeClass.TEMPERATURE, "O3支管温度",
                ModbusDataType.FLOAT, 2, TemperatureUnit.CELSIUS, false));
        
        // 震动 - 地址223/224，只读
        attributeMap.put(223, new AttributeInfo("vibration", AttributeClass.VIBRATION, "震动",
                ModbusDataType.FLOAT, 2, SpeedUnit.MILLIMETER_PER_SECOND, false));
        
        // PM10标况流量 - 地址225/226，只读
        attributeMap.put(225, new AttributeInfo("pm10_std_flow", AttributeClass.FLOW, "PM10标况流量",
                ModbusDataType.FLOAT, 2, LiterFlowUnit.L_PER_MINUTE, false, 2));

        attributeMap.put(227, new AttributeInfo("pm10_working_flow", AttributeClass.FLOW, "PM10工况流量",
                ModbusDataType.FLOAT, 2, LiterFlowUnit.L_PER_MINUTE, false, 2));
        
        // PM2.5工况流量 - 地址231/232，只读
        attributeMap.put(229, new AttributeInfo("pm2_5_std_flow", AttributeClass.FLOW, "PM2.5标况流量",
                ModbusDataType.FLOAT, 2, LiterFlowUnit.L_PER_MINUTE, false, 2));

        attributeMap.put(231, new AttributeInfo("pm2_5_working_flow", AttributeClass.FLOW, "PM2.5工况流量",
                ModbusDataType.FLOAT, 2, LiterFlowUnit.L_PER_MINUTE, false, 2));

        registerConfigDerivedAttributes();
    }

    /**
     * 注册非 Modbus、由配置或计算得到的属性（仅创建并注册，不赋值）。
     * <p>配置派生属性的业务值改在 {@link #initConfigDerivedAttributeValues()} 写入——必须在设备 id 解析
     * （addDevice/getOrCreate）之后：{@code AttributeBase.updateValue} 要求 {@code attr.device != null}，
     * 且 {@code AttrState.deviceId} 必填持久化 id；构造期 id 尚未解析，此时 updateValue 会跳过 midState
     * 构建导致 state 为 null。计算属性（residence_time）由 readRegisters 周期重算，无需赋初值。
     */
    private void registerConfigDerivedAttributes() {
        setAttribute(new NumericAttribute("tube_length", "采样管长度", AttributeClass.NUMERIC,
                NoConversionUnit.of("m", "米"), NoConversionUnit.of("m", "米"), 2, false, false));
        setAttribute(new NumericAttribute("tube_inner_diameter", "采样管内径", AttributeClass.NUMERIC,
                NoConversionUnit.of("m", "米"), NoConversionUnit.of("m", "米"), 3, false, false));
        setAttribute(new NumericAttribute("sampling_tube_residence_time", AttributeClass.TIME,
                NoConversionUnit.of("s", "秒"), NoConversionUnit.of("s", "秒"), 1, false, false));
        // 采样管流量：由采样管流速（sample_tube_flow, m/s）与内径（tube_inner_diameter, m）算得的体积流量，单位 L/min
        setAttribute(new NumericAttribute("sample_tube_volume_flow", "采样管流量", AttributeClass.FLOW,
                LiterFlowUnit.L_PER_MINUTE, LiterFlowUnit.L_PER_MINUTE, 2, false, false));
    }

    /**
     * 写入配置派生属性的业务值。在 {@link #start()} 调用——此时设备已经过 addDevice/getOrCreate
     * 解析出持久化 id，{@code updateValue} 构建的 midState 携带正确的 deviceId。
     * reconfigure 重建设备后走到 start 也会用新配置重写值。
     */
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

    /**
     * 动态创建属性（关键优化：根据dataType选择属性类）
     */
    private void createAttributes() {
        for (Map.Entry<Integer, AttributeInfo> entry : attributeMap.entrySet()) {
            Integer address = entry.getKey();
            AttributeInfo info = entry.getValue();
            createAttribute(info, address);
        }
        for (Map.Entry<Integer, List<AttributeInfo>> entry : duplicateMap.entrySet()) {
            Integer address = entry.getKey();
            List<AttributeInfo> infos = entry.getValue();
            for (AttributeInfo info : infos) {
                createAttribute(info, address);
            }
        }
    }

    private void createAttribute(AttributeInfo info, Integer address) {
        switch (info.dataType) {
            case FLOAT:
                setAttribute(new ModbusFloatAttribute(
                    info.attributeId, info.attrClass,
                    info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                    modbusSource, address.shortValue(), bigConverter
                ));
                break;

            case U16X10:
                setAttribute(new ModbusScalableFloatSRAttribute(
                    info.attributeId, info.attrClass,
                    info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                    modbusSource, address.shortValue(), bigConverter, 10.0f
                ));
                break;

            case U16X100:
                setAttribute(new ModbusScalableFloatSRAttribute(
                    info.attributeId, info.attrClass,
                    info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                    modbusSource, address.shortValue(), bigConverter, 100.0f
                ));
                break;

            case U16X1:
                setAttribute(new ModbusScalableFloatSRAttribute(
                    info.attributeId, info.attrClass,
                    info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                    modbusSource, address.shortValue(), bigConverter, 1.0f
                ));
                break;

            case U16:
                setAttribute(new ModbusShortAttribute(
                    info.attributeId, info.attrClass,
                    info.unitType, info.unitType, info.displayPrecision, false, info.isWritable,
                    modbusSource, address.shortValue()
                ));
                break;
        }
    }

    /**
     * 定时读取Modbus寄存器数据（SDK 单事务 round：两块读在同源锁内 FIFO 串行，
     * 块间 1s 节拍保留——设备性能要求。旧形态为两笔独立事务在 1s 定时到点
     * 盲发第二笔，块一未完成时第二笔 tryAcquire 必锁忙静默缺失（wit-motion 同型缺陷），
     * 合并后天然串行不再互踩）
     */
    protected CompletableFuture<Boolean> readRegisters(ModbusPolling polling, ModbusSource source) {
        // 第一块：前 110 个参数（失败不阻断后续块——旧形态块一为 fire-and-forget，异常被丢弃）
        return source.readHoldingRegisters(FIRST_BLOCK_START, FIRST_BLOCK_COUNT)
                .thenApply(firstResponse -> {
                    try {
                        // 处理第一块数据
                        short[] firstBlockRegisters = firstResponse.getShortData();
                        log.debug("QCDevice 第一块数据: {} 长度: {}", Arrays.toString(firstBlockRegisters), firstBlockRegisters.length);
                        parseBlockData(firstBlockRegisters, FIRST_BLOCK_START);
                        return true;
                    } catch (Exception e) {
                        log.error("QCDevice 第一块数据解析失败: " + e.getMessage());
                        getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                        publicAttrsState();
                        return false;
                    }
                })
                .handle((ok, ex) -> {
                    if (ex != null) {
                        log.error("QCDevice 第一块数据读取失败，继续后续块: " + ex.getMessage());
                    }
                    return true;
                })
                // 块间 1s 节拍后读第二块（110~232；secondBlockGapMs：设备性能要求默认 1000，测试可注入）
                .thenCompose(v -> polling.delay(secondBlockGapMs).thenCompose(z ->
                        source.readHoldingRegisters(SECOND_BLOCK_START, SECOND_BLOCK_COUNT)
                                .thenApply(secondResponse -> {
                                    try {
                                        // 处理第二块数据
                                        short[] secondBlockRegisters = secondResponse.getShortData();
                                        log.debug("{} 第二块数据: {} 长度: {}", getClass().getSimpleName(),
                                                Arrays.toString(secondBlockRegisters), secondBlockRegisters.length);
                                        parseBlockData(secondBlockRegisters, SECOND_BLOCK_START);
                                        finishReadCycle();
                                        return true;
                                    } catch (Exception e) {
                                        log.error("{} 第二块数据解析失败: {}", getClass().getSimpleName(), e.getMessage());
                                        getAttrs().values()
                                                .forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                                        publicAttrsState();
                                        return false;
                                    }
                                })));
    }

    /** 两块读取完成后：计算派生属性、置 NORMAL 并发布 */
    private void finishReadCycle() {
        updateCalulateAttr();
        getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.NORMAL));
        publicAttrsState();
    }

    /**
     * 解析数据块
     */
    private void parseBlockData(short[] registers, int startAddress) {
        AttributeStatus status = AttributeStatus.NORMAL;
        for (int i = 0; i < registers.length; i++) {
            int address = startAddress + i;
            if (attributeMap.containsKey(address)) {
                AttributeInfo info = attributeMap.get(address);
                switch (info.dataType) {
                    case FLOAT:
                        if (i + 1 < registers.length) {
                            float value = Tools.convertBigEndianToFloat(registers[i], registers[i+1]);
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
                        short u16Value = registers[i];
                        updateModbusShortAttribute(info.attributeId, u16Value, status);
                        break;
                }
            }
        }
        for (int i = 0; i < registers.length; i++) {
            int address = startAddress + i;
            if (duplicateMap.containsKey(address)) {
                List<AttributeInfo> infos = duplicateMap.get(address);
                for (AttributeInfo info : infos) {
                    switch (info.dataType) {
                        case FLOAT:
                        if (i + 1 < registers.length) {
                            float value = Tools.convertBigEndianToFloat(registers[i], registers[i + 1]);
                            updateModbusFloatAttribute(info.attributeId, value, status);
                            i++;
                        }
                        break;

                        case U16:
                        short u16Value = registers[i];
                        updateModbusShortAttribute(info.attributeId, u16Value, status);
                        break;

                        case U16X10:
                        case U16X100:
                        case U16X1:
                        updateModbusScalableAttribute(info.attributeId, registers[i], status);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 更新float类型属性值
     */
    private void updateModbusFloatAttribute(String attributeId, float value, AttributeStatus status) {
        ModbusFloatAttribute attr = (ModbusFloatAttribute) getAttrs().get(attributeId);
        if (attr != null) {
            attr.updateValue(value, status);
        }
    }

    /**
     * 更新short类型属性值
     */
    private void updateModbusShortAttribute(String attributeId, short value, AttributeStatus status) {
        ModbusShortAttribute attr = (ModbusShortAttribute) getAttrs().get(attributeId);
        if (attr != null) {
            attr.updateValue(value, status);
        }
    }

    /**
     * 更新带缩放系数的 U16 属性。系数在建属性时写入（1 / 10 / 100），此处只投递原始寄存器值。
     */
    private void updateModbusScalableAttribute(String attributeId, short value, AttributeStatus status) {
        ModbusScalableFloatSRAttribute attr = (ModbusScalableFloatSRAttribute) getAttrs().get(attributeId);
        if (attr != null) attr.updateValue(value, status);
    }

    private void updateCalulateAttr() {
        // 采样管长度/内径为不变配置值，随每次流速更新一并刷新（原仅在 start 初始化写一次）
        NumericAttribute tubeLengthAttr = (NumericAttribute) getAttrs().get("tube_length");
        if (tubeLengthAttr != null) {
            tubeLengthAttr.updateValue(deviceConfig.getSamplingTubeLength(), AttributeStatus.NORMAL);
        }
        NumericAttribute tubeDiameterAttr = (NumericAttribute) getAttrs().get("tube_inner_diameter");
        if (tubeDiameterAttr != null) {
            tubeDiameterAttr.updateValue(deviceConfig.getSamplingTubeInnerDiameter(), AttributeStatus.NORMAL);
        }

        // 计算总采样管滞留时间
        ModbusFloatAttribute samplingTubeFlowAttr = (ModbusFloatAttribute) getAttrs().get("sample_tube_flow");
        if (samplingTubeFlowAttr != null) {
            Double residenceTime = 999.0; // 默认值
            double volumeFlow = 0.0; // 采样管流量（L/min）默认值
            // 从不可变 state 读，getValue 已封装为 protected
            AttrState<?> samplingTubeFlowState = samplingTubeFlowAttr.getState();
            Float samplingTubeFlow = samplingTubeFlowState != null ? (Float) samplingTubeFlowState.getValue() : null;
            if( samplingTubeFlow == null || samplingTubeFlow <= 0) {
                log.warn("采样管流速为0或null，无效，无法计算滞留时间与流量，滞留时间置极大默认值、流量置0");
            }
            else{
                residenceTime = deviceConfig.getSamplingTubeLength() / samplingTubeFlow;
                // 流量(L/min) = 截面积(m²) × 流速(m/s) × 60000；截面积 = π × 内径(m)² / 4
                double innerDiameter = deviceConfig.getSamplingTubeInnerDiameter();
                double crossSectionArea = Math.PI * innerDiameter * innerDiameter / 4.0;
                volumeFlow = crossSectionArea * samplingTubeFlow * 60000.0;
            }

            NumericAttribute timeAttr = (NumericAttribute) getAttrs().get("sampling_tube_residence_time");
            timeAttr.updateValue(residenceTime, AttributeStatus.NORMAL);

            NumericAttribute volumeFlowAttr = (NumericAttribute) getAttrs().get("sample_tube_volume_flow");
            if (volumeFlowAttr != null) {
                volumeFlowAttr.updateValue(volumeFlow, AttributeStatus.NORMAL);
            }
        }

        // TODO: 这里的流量是一个手动标定的转换系数，实际应用中需要根据具体情况调整，后面要优化
        // 从不可变 state 读，getValue 已封装为 protected；state 为 null 时跳过标定避免 NPE
        ModbusFloatAttribute pm10StdFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm10_std_flow");
        if (pm10StdFlowAttr.getState() != null) {
            pm10StdFlowAttr.updateValue((Float) pm10StdFlowAttr.getState().getValue() * 1.021f);
        }
        ModbusFloatAttribute pm10WorkingFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm10_working_flow");
        if (pm10WorkingFlowAttr.getState() != null) {
            pm10WorkingFlowAttr.updateValue((Float) pm10WorkingFlowAttr.getState().getValue() * 1.021f);
        }
        ModbusFloatAttribute pm2_5StdFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm2_5_std_flow");
        if (pm2_5StdFlowAttr.getState() != null) {
            pm2_5StdFlowAttr.updateValue((Float) pm2_5StdFlowAttr.getState().getValue() * 0.997f);
        }
        ModbusFloatAttribute pm2_5WorkingFlowAttr = (ModbusFloatAttribute) getAttrs().get("pm2_5_working_flow");
        if (pm2_5WorkingFlowAttr.getState() != null) {
            pm2_5WorkingFlowAttr.updateValue((Float) pm2_5WorkingFlowAttr.getState().getValue() * 0.997f);
        }

    }

    /**
     * 属性信息类（包含中文显示名称）
     */
    protected static class AttributeInfo {
        String attributeId;
        AttributeClass attrClass;
        ModbusDataType dataType;
        @SuppressWarnings("unused")
        int registerCount;
        UnitInfo unitType;
        boolean isWritable;
        int displayPrecision; // 新增：显示小数位数

        public AttributeInfo(String attributeId, AttributeClass attrClass, String displayName,
                ModbusDataType dataType, int registerCount, UnitInfo unitType, boolean isWritable) {
            this(attributeId, attrClass, displayName, dataType, registerCount, unitType, isWritable, 1);
        }

        public AttributeInfo(String attributeId, AttributeClass attrClass, String displayName,
                ModbusDataType dataType, int registerCount, UnitInfo unitType, boolean isWritable, int displayPrecision) {
            this.attributeId = attributeId;
            this.attrClass = attrClass;
            this.dataType = dataType;
            this.registerCount = registerCount;
            this.unitType = unitType;
            this.isWritable = isWritable;
            this.displayPrecision = displayPrecision;
        }
    }
    
    /**
     * Modbus数据类型枚举
     */
    protected enum ModbusDataType {
        U16,
        U16X1,
        U16X10,
        U16X100,
        FLOAT
    }

    /**
     * 获取配置定义（包含必要的的YAML配置验证规则）
     */
    public ConfigDefinition getConfigDefinition() {
        if (configDefinition == null) {
            configDefinition = new ConfigDefinition();

            // 定义 device_settings 配置项
            ConfigItemBuilder deviceConfig = new ConfigItemBuilder()
                    .add(new ConfigItem<>("sampling_tube_length", Double.class, true, null))
                    .add(new ConfigItem<>("sampling_tube_inner_diameter", Double.class, true, null));
            
            // 定义每个校准项的配置项
            ConfigItemBuilder config = new ConfigItemBuilder()
                    .add(new ConfigItem<>("device_settings", Map.class, true, null)
                            .addNestedConfigItems(deviceConfig));
            
            configDefinition.define(config);
        }
        return configDefinition;
    }

    /**
     * 将Map配置转换为deviceConfig对象
     */
    @SuppressWarnings("unchecked")
    private DeviceConfig parseConfig(Map<String, Object> config) {
        if(configDefinition.validateConfig(config)) {
            log.info("配置验证通过，开始解析配置");
        } else {
            log.error("配置验证失败，无法解析配置");
            throw new IllegalArgumentException("配置验证失败." + 
                configDefinition.getInvalidConfigItems().entrySet().stream()
                    .map(entry -> "配置项: " + entry.getKey().getKey() + ", 错误信息: " + entry.getValue())
                    .collect(Collectors.joining(", ")));
        }
        
        DeviceConfig deviceConfig = new DeviceConfig();
        
        // 解析 device_settings 配置
        Map<String, Object> dsConfig = (Map<String, Object>) config.get("device_settings");
        deviceConfig.setSamplingTubeLength((double) dsConfig.getOrDefault("sampling_tube_length", 4.5));
        deviceConfig.setSamplingTubeInnerDiameter((double) dsConfig.getOrDefault("sampling_tube_inner_diameter", 0.03));

        return deviceConfig;
    }

    // 配置数据类
    @Getter
    @Setter
    public static class DeviceConfig {
        private Double samplingTubeLength;
        private Double samplingTubeInnerDiameter;
    }
}
