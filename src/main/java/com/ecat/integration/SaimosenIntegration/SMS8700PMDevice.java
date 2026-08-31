package com.ecat.integration.SaimosenIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.State.AttributeAbility;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.Unit.AirMassUnit;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.core.State.Unit.RatioUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Tools;

/**
 * SMS8700 多粒径颗粒物自动监测仪（Modbus RTU 从站对外输出协议 v1.2）。
 * <p>
 * 一次读取保持寄存器 0~27（共 28 个）：PM 浓度与工况参数均为 IEEE754 大端 float。
 * 物理设备同时暴露 {@code pm10}/{@code pm2_5} 等浓度，供 PM10/PM2.5 逻辑设备绑定。
 * </p>
 */
public class SMS8700PMDevice extends SmsDeviceBase {

    /** 轮询周期（毫秒）。生产=标准采集频率 10s；单测注入短周期压缩负向等待窗。 */
    protected long pollPeriodMs = 10_000L;

    private static final String STATUS_PREFIX = "pm";

    /** 寄存器 0~27，协议单次最大读取数。 */
    static final int REG_BLOCK_START = 0;
    static final int REG_BLOCK_COUNT = 28;

    static final int IDX_PM1 = 0;
    static final int IDX_PM25 = 2;
    static final int IDX_PM4 = 4;
    static final int IDX_PM10 = 6;
    static final int IDX_PMTOT = 8;
    static final int IDX_DATA_VALID = 10;
    static final int IDX_SECONDS_SINCE_PM = 11;
    static final int IDX_MAP_VERSION = 12;
    static final int IDX_DEVICE_STATUS = 13;
    static final int IDX_SAMPLE_FLOW = 14;
    static final int IDX_SAMPLE_TEMP = 16;
    static final int IDX_SAMPLE_HUMI = 18;
    static final int IDX_ENV_TEMP = 20;
    static final int IDX_ENV_HUMI = 22;
    static final int IDX_PRESSURE = 24;
    static final int IDX_SLAVE_ADDR = 26;

    /** DEVICE_STATUS bit0：曾成功收到 PM 数据。 */
    static final int STATUS_BIT_PM_VALID = 1;
    /** DEVICE_STATUS bit1：控制板工况有效。 */
    static final int STATUS_BIT_CTRL_VALID = 1 << 1;
    /** DEVICE_STATUS bit2：PM 数据超时未更新。 */
    static final int STATUS_BIT_PM_STALE = 1 << 2;

    public SMS8700PMDevice(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        // 周期轮询（pollPeriodMs，生产默认 10s）：调度注册/源锁/锁忙跳过/异常韧性/统一日志全部由 ModbusPolling SDK 托管
        ModbusPolling.on(this, modbusSource)
                .round(this::readAndUpdate)
                .every(pollPeriodMs, TimeUnit.MILLISECONDS)
                .start();
    }

    @Override
    public void stop() {
        // 轮询生命周期已由 ModbusPolling SDK 内绑 RemovalHost（设备移除 sweep）收尾
    }

    private void createAttributes() {
        setAttribute(new NumericAttribute("pm1", AttributeClass.PM, AirMassUnit.UGM3, AirMassUnit.UGM3, 2, false, false));
        setAttribute(new NumericAttribute("pm2_5", AttributeClass.PM2_5, AirMassUnit.UGM3, AirMassUnit.UGM3, 2, false, false));
        setAttribute(new NumericAttribute("pm4", AttributeClass.PM, AirMassUnit.UGM3, AirMassUnit.UGM3, 2, false, false));
        setAttribute(new NumericAttribute("pm10", AttributeClass.PM10, AirMassUnit.UGM3, AirMassUnit.UGM3, 2, false, false));
        setAttribute(new NumericAttribute("pm_tot", AttributeClass.PM, AirMassUnit.UGM3, AirMassUnit.UGM3, 2, false, false));

        setAttribute(new NumericAttribute("data_valid", AttributeClass.STATUS, NoConversionUnit.of(""), NoConversionUnit.of(""), 0, false, false));
        setAttribute(new NumericAttribute("seconds_since_pm", AttributeClass.VALUE, NoConversionUnit.of("s"), NoConversionUnit.of("s"), 0, false, false));
        setAttribute(new NumericAttribute("map_version", AttributeClass.VALUE, NoConversionUnit.of(""), NoConversionUnit.of(""), 0, false, false));
        setAttribute(new NumericAttribute("device_status_bits", AttributeClass.STATUS, NoConversionUnit.of(""), NoConversionUnit.of(""), 0, false, false));

        setAttribute(new NumericAttribute("sample_flow", AttributeClass.FLOW, LiterFlowUnit.L_PER_MINUTE, LiterFlowUnit.L_PER_MINUTE, 3, false, false));
        setAttribute(new NumericAttribute("sample_temp", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 2, false, false));
        setAttribute(new NumericAttribute("sample_humidity", AttributeClass.HUMIDITY, RatioUnit.PERCENT, RatioUnit.PERCENT, 2, false, false));
        setAttribute(new NumericAttribute("ambient_temperature", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 2, false, false));
        setAttribute(new NumericAttribute("ambient_humidity", AttributeClass.HUMIDITY, RatioUnit.PERCENT, RatioUnit.PERCENT, 2, false, false));
        setAttribute(new NumericAttribute("barometric_pressure", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 2, false, false));
        setAttribute(new NumericAttribute("slave_addr_echo", AttributeClass.VALUE, NoConversionUnit.of(""), NoConversionUnit.of(""), 0, false, false));

        addManualStatusAttributes(STATUS_PREFIX);
        addGeneralAlarmAttribute();
    }

    CompletableFuture<Boolean> readAndUpdate(ModbusSource source) {
        return source.readHoldingRegisters(REG_BLOCK_START, REG_BLOCK_COUNT)
                .thenApply(response -> applyRegisters(response.getShortData()))
                .exceptionally(ex -> {
                    log.warn("SMS8700PMDevice " + getId() + " read failed: " + ex.getMessage());
                    setAllMalfunction();
                    return false;
                });
    }

    /**
     * 解析并更新属性；供单测直接注入寄存器数据。
     *
     * @return true 表示解析成功并已提交状态
     */
    boolean applyRegisters(short[] regs) {
        if (regs == null || regs.length < REG_BLOCK_COUNT) {
            setAllMalfunction();
            return false;
        }
        try {
            int dataValid = regs[IDX_DATA_VALID] & 0xFFFF;
            int secondsSincePm = regs[IDX_SECONDS_SINCE_PM] & 0xFFFF;
            int mapVersion = regs[IDX_MAP_VERSION] & 0xFFFF;
            int deviceStatusBits = regs[IDX_DEVICE_STATUS] & 0xFFFF;
            int slaveAddr = regs[IDX_SLAVE_ADDR] & 0xFFFF;

            boolean pmOk = dataValid == 1 && (deviceStatusBits & STATUS_BIT_PM_STALE) == 0;
            boolean ctrlOk = (deviceStatusBits & STATUS_BIT_CTRL_VALID) != 0;

            // 寄存器读成功即为通讯正常；PM/工况有效性分别体现在各属性状态上
            AttributeStatus autoStatus = AttributeStatus.NORMAL;
            AttributeStatus resolved = determineAttributeStatus(
                    autoStatus, STATUS_PREFIX + "_manual_status", "general_alarm");
            updateReadonlyStatusAttribute(STATUS_PREFIX + "_status", resolved);

            // 无效 PM 不上报为正常浓度；手动非 NORMAL 状态（维护等）仍可覆盖
            AttributeStatus pmStatus = resolveChannelStatus(pmOk, resolved);
            updateNum("pm1", readFloatBe(regs, IDX_PM1), pmStatus);
            updateNum("pm2_5", readFloatBe(regs, IDX_PM25), pmStatus);
            updateNum("pm4", readFloatBe(regs, IDX_PM4), pmStatus);
            updateNum("pm10", readFloatBe(regs, IDX_PM10), pmStatus);
            updateNum("pm_tot", readFloatBe(regs, IDX_PMTOT), pmStatus);

            updateNum("data_valid", dataValid, resolved);
            updateNum("seconds_since_pm", secondsSincePm, resolved);
            updateNum("map_version", mapVersion, resolved);
            updateNum("device_status_bits", deviceStatusBits, resolved);
            updateNum("slave_addr_echo", slaveAddr, resolved);

            AttributeStatus ctrlStatus = resolveChannelStatus(ctrlOk, resolved);
            updateNum("sample_flow", readFloatBe(regs, IDX_SAMPLE_FLOW), ctrlStatus);
            updateNum("sample_temp", readFloatBe(regs, IDX_SAMPLE_TEMP), ctrlStatus);
            updateNum("sample_humidity", readFloatBe(regs, IDX_SAMPLE_HUMI), ctrlStatus);
            updateNum("ambient_temperature", readFloatBe(regs, IDX_ENV_TEMP), ctrlStatus);
            updateNum("ambient_humidity", readFloatBe(regs, IDX_ENV_HUMI), ctrlStatus);
            updateNum("barometric_pressure", readFloatBe(regs, IDX_PRESSURE), ctrlStatus);

            this.deviceStatus = pmOk ? DeviceStatus.MEASURE : DeviceStatus.ALARM;
            DeviceStatus mapped = mapAttributeStatusToDeviceStatus(resolved);
            if (mapped != null && mapped != DeviceStatus.UNKNOWN && resolved != AttributeStatus.NORMAL) {
                this.deviceStatus = mapped;
            }

            commitPollState();
            return true;
        } catch (Exception e) {
            log.error("SMS8700PMDevice parse error", e);
            setAllMalfunction();
            return false;
        }
    }

    /**
     * 通道有效时跟随 resolved；无效时默认 MALFUNCTION，仅保留手动覆盖的业务状态。
     */
    static AttributeStatus resolveChannelStatus(boolean channelOk, AttributeStatus resolved) {
        if (channelOk) {
            return resolved;
        }
        if (resolved != null
                && resolved != AttributeStatus.NORMAL
                && resolved != AttributeStatus.MALFUNCTION
                && resolved != AttributeStatus.ALARM) {
            return resolved;
        }
        return AttributeStatus.MALFUNCTION;
    }

    static float readFloatBe(short[] regs, int wordIndex) {
        return Tools.convertBigEndianToFloat(regs[wordIndex], regs[wordIndex + 1]);
    }

    private void updateNum(String id, double v, AttributeStatus st) {
        AttributeAbility<?> attr = getAttrs().get(id);
        if (attr instanceof NumericAttribute) {
            ((NumericAttribute) attr).updateValue(v, st);
        }
    }

    private void setAllMalfunction() {
        getAttrs().values().forEach(a -> a.setStatus(AttributeStatus.MALFUNCTION));
        this.deviceStatus = DeviceStatus.ALARM;
        // 通讯失败不得刷新 lastUpdated（online 依赖超时判离线）
    }
}
