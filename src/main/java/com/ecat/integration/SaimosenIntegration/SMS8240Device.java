package com.ecat.integration.SaimosenIntegration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.EcatCore;
import com.ecat.core.State.AttributeAbility;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.NoConversionUnit;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusFloatAttribute;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusUnsignedShortAttribute;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;
import com.ecat.integration.ModbusIntegration.EndianConverter.EndianConverter;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers;

/**
 * 赛默森 SMS8240 双路颗粒物质控设备（双路颗粒物质控通信协议，Modbus RTU 9600-8-N-1）。
 *
 * <p>保持寄存器 {@code 0x0000~0x0012} 共 19 个：
 * <ul>
 *   <li>两类流量各两路（矫正标况 / 工况，Float32 大端 ABCD）</li>
 *   <li>两路管路控制/状态（U16，<b>0 = 贯通、1 = 关闭</b>，可读写）</li>
 *   <li>采集目标地址、采集状态、采集板地址（U16，只读）</li>
 *   <li>两路原始标况流量（Float32，<b>协议注明固件尚未实现</b>）</li>
 * </ul>
 *
 * <p><b>能力降级</b>：先读 19 个；扩展块不可用（旧固件返回异常码 / 短帧）时自动改读
 * 核心 15 个（{@code 0x0000~0x000E}），原始标况保持空白；降级后不每轮重试全量，
 * 按 {@link #EXTENDED_PROBE_INTERVAL_MS} 低频探测一次，便于固件升级后自动恢复。
 *
 * <p><b>状态判定</b>：该路采集状态 = 1 时流量为有效值（<b>0.0 也是有效值</b>）；
 * = 0 时该路三类流量统一落 {@code ALARM}，避免"采集失败的 0 流量"被当成真实读数入库。
 *
 * <p><b>启动回正</b>：协议规定上电两路贯通、通信中断保持当前状态。平台重启但板子没断电时，
 * 板子会停在"关闭"。因此 {@code start()} 后延迟 {@link #restoreOpenDelayMs} 毫秒，
 * 对仍处于关闭的通道写 0（可撤销定时器，设备移除 sweep 会撤销待发动作）。
 *
 * @author coffee
 */
public class SMS8240Device extends SmsDeviceBase {

    /** 型号标识：{@code CLASS_MODEL_MAP} 与逻辑映射共用这一处字面量。 */
    public static final String MODEL = "SMS8240";

    /** 出厂采集节律（秒）：板内缓存每 2 秒刷新一次，轮询不快于 1s。 */
    public static final int FACTORY_POLL_INTERVAL_SEC = 2;

    /** 扩展块起始地址与数量（{@code 0x0000~0x0012}）。 */
    static final int REG_EXT_START = 0x0000;
    static final int REG_EXT_COUNT = 0x0013;
    /** 核心块数量（{@code 0x0000~0x000E}，含采集板地址）。 */
    static final int REG_CORE_COUNT = 0x000F;

    /** 扩展块降级后的低频探测周期（毫秒）。 */
    static final long EXTENDED_PROBE_INTERVAL_MS = 5 * 60 * 1000L;

    /**
     * 启动回正延迟（毫秒）。包可见 volatile 测试缝：单测注入短延迟，验证"设备 stop +
     * 框架 sweep 后待发回正拍被撤销"，不必等生产 10s。
     */
    static volatile long restoreOpenDelayMs = 10_000L;

    // ---- 块内索引（以 19 寄存器块为例）----
    static final int IDX_PM10_CORRECTED_STD_FLOW = 0;
    static final int IDX_PM10_WORKING_FLOW = 2;
    static final int IDX_PM25_CORRECTED_STD_FLOW = 4;
    static final int IDX_PM25_WORKING_FLOW = 6;
    static final int IDX_PM10_PIPE_STATE = 8;
    static final int IDX_PM25_PIPE_STATE = 9;
    static final int IDX_PM10_TARGET_ADDRESS = 10;
    static final int IDX_PM25_TARGET_ADDRESS = 11;
    static final int IDX_PM10_COLLECT_STATUS = 12;
    static final int IDX_PM25_COLLECT_STATUS = 13;
    static final int IDX_BOARD_SLAVE_ADDRESS = 14;
    static final int IDX_PM10_RAW_STD_FLOW = 15;
    static final int IDX_PM25_RAW_STD_FLOW = 17;

    // ---- 物理属性 ID ----
    static final String A_PM10_CORRECTED_STD_FLOW = "pm10_corrected_std_flow";
    static final String A_PM10_WORKING_FLOW = "pm10_working_flow";
    static final String A_PM25_CORRECTED_STD_FLOW = "pm2_5_corrected_std_flow";
    static final String A_PM25_WORKING_FLOW = "pm2_5_working_flow";
    static final String A_PM10_RAW_STD_FLOW = "pm10_raw_std_flow";
    static final String A_PM25_RAW_STD_FLOW = "pm2_5_raw_std_flow";
    static final String A_PM10_PIPE_STATE = "pm10_pipe_state";
    static final String A_PM25_PIPE_STATE = "pm2_5_pipe_state";
    static final String A_PM10_TARGET_ADDRESS = "pm10_target_address";
    static final String A_PM25_TARGET_ADDRESS = "pm2_5_target_address";
    static final String A_PM10_COLLECT_STATUS = "pm10_collect_status";
    static final String A_PM25_COLLECT_STATUS = "pm2_5_collect_status";
    static final String A_BOARD_SLAVE_ADDRESS = "board_slave_address";

    private static final EndianConverter ENDIAN = new BigEndianConverter();

    /** 仪表实际输出流量单位（双通道共用），逻辑层恒换算为 L/min。 */
    private LiterFlowUnit flowUnit = LiterFlowUnit.L_PER_MINUTE;

    /** 启动时是否自动回正贯通。 */
    private boolean restoreOpenOnStart = true;

    /** 扩展块（原始标况）当前是否可用。 */
    private volatile boolean extendedBlockSupported = true;

    /** 上次扩展块能力探测时刻（毫秒）。 */
    private volatile long lastExtendedProbeMs = 0L;

    public SMS8240Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public void load(EcatCore core) {
        super.load(core);
        // 板内 2s 刷新；poll_interval_sec 缺省/非法时按出厂档兜底
        pollIntervalMs = resolvePollIntervalMs(config, FACTORY_POLL_INTERVAL_SEC);
        this.flowUnit = resolveFlowUnit(config);
        this.restoreOpenOnStart = resolveRestoreOpenOnStart(config);
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        ModbusPolling.on(this, modbusSource)
                .round(this::readAndUpdate)
                .every(pollIntervalMs, TimeUnit.MILLISECONDS)
                .start();

        if (restoreOpenOnStart) {
            // 可撤销延迟：设备移除 sweep 撤销待发回正拍（与 ParticulateZeroChecker 同款范式）
            ModbusSdkTimers.delay(this, restoreOpenDelayMs).thenRun(this::restoreOpenBestEffort);
        }
    }

    @Override
    public void stop() {
        // 轮询生命周期已由 ModbusPolling SDK 内绑 RemovalHost（设备移除 sweep）收尾；停机不写寄存器
    }

    private void createAttributes() {
        // 三类流量：单位 = 仪表实际输出单位（flow_unit），逻辑层读时换算为 L/min
        setAttribute(floatAttr(A_PM10_CORRECTED_STD_FLOW, IDX_PM10_CORRECTED_STD_FLOW));
        setAttribute(floatAttr(A_PM10_WORKING_FLOW, IDX_PM10_WORKING_FLOW));
        setAttribute(floatAttr(A_PM25_CORRECTED_STD_FLOW, IDX_PM25_CORRECTED_STD_FLOW));
        setAttribute(floatAttr(A_PM25_WORKING_FLOW, IDX_PM25_WORKING_FLOW));
        setAttribute(floatAttr(A_PM10_RAW_STD_FLOW, IDX_PM10_RAW_STD_FLOW));
        setAttribute(floatAttr(A_PM25_RAW_STD_FLOW, IDX_PM25_RAW_STD_FLOW));

        // 管路控制：可读写，0=贯通 / 1=关闭，写后同锁回读确认
        setAttribute(new ConfirmedPipeControlAttribute(A_PM10_PIPE_STATE, modbusSource, IDX_PM10_PIPE_STATE));
        setAttribute(new ConfirmedPipeControlAttribute(A_PM25_PIPE_STATE, modbusSource, IDX_PM25_PIPE_STATE));

        // 只读诊断/配置信息：目标地址、采集状态、采集板地址（平台不提供地址写入口）
        setAttribute(u16Attr(A_PM10_TARGET_ADDRESS, IDX_PM10_TARGET_ADDRESS, AttributeClass.VALUE));
        setAttribute(u16Attr(A_PM25_TARGET_ADDRESS, IDX_PM25_TARGET_ADDRESS, AttributeClass.VALUE));
        setAttribute(u16Attr(A_PM10_COLLECT_STATUS, IDX_PM10_COLLECT_STATUS, AttributeClass.STATUS));
        setAttribute(u16Attr(A_PM25_COLLECT_STATUS, IDX_PM25_COLLECT_STATUS, AttributeClass.STATUS));
        setAttribute(u16Attr(A_BOARD_SLAVE_ADDRESS, IDX_BOARD_SLAVE_ADDRESS, AttributeClass.VALUE));
    }

    private ModbusFloatAttribute floatAttr(String id, int registerAddress) {
        return new ModbusFloatAttribute(id, AttributeClass.FLOW,
                flowUnit, flowUnit, 3, false, false, modbusSource, (short) registerAddress, ENDIAN);
    }

    private ModbusUnsignedShortAttribute u16Attr(String id, int registerAddress, AttributeClass attrClass) {
        return new ModbusUnsignedShortAttribute(id, attrClass,
                NoConversionUnit.of(""), NoConversionUnit.of(""), 0,
                false, false, modbusSource, registerAddress);
    }

    // ==================== 轮询与固件降级 ====================

    CompletableFuture<Boolean> readAndUpdate(ModbusSource source) {
        final long now = System.currentTimeMillis();
        boolean tryExtended = extendedBlockSupported
                || now - lastExtendedProbeMs >= EXTENDED_PROBE_INTERVAL_MS;
        if (!tryExtended) {
            return readCoreBlock(source);
        }
        return source.readHoldingRegisters(REG_EXT_START, REG_EXT_COUNT)
                .handle((response, ex) -> {
                    if (ex != null) {
                        log.warn("SMS8240Device " + getId() + " 扩展块读取异常，降级核心块: " + ex.getMessage());
                        return null;
                    }
                    if (response == null || response.isException()
                            || response.getShortData() == null
                            || response.getShortData().length < REG_EXT_COUNT) {
                        log.warn("SMS8240Device " + getId() + " 扩展块不可用，降级核心块: "
                                + (response == null ? "无响应" : response.getExceptionMessage()));
                        return null;
                    }
                    return response.getShortData();
                })
                .thenCompose(regs -> {
                    if (regs == null) {
                        if (extendedBlockSupported) {
                            log.info("SMS8240Device " + getId() + " 固件不支持原始标况扩展块，"
                                    + "改读核心 15 寄存器，低频重试周期=" + EXTENDED_PROBE_INTERVAL_MS + "ms");
                        }
                        extendedBlockSupported = false;
                        lastExtendedProbeMs = now;
                        return readCoreBlock(source);
                    }
                    extendedBlockSupported = true;
                    lastExtendedProbeMs = now;
                    return CompletableFuture.completedFuture(applyRegisters(regs, true));
                });
    }

    private CompletableFuture<Boolean> readCoreBlock(ModbusSource source) {
        return source.readHoldingRegisters(REG_EXT_START, REG_CORE_COUNT)
                .thenApply(response -> {
                    if (response == null || response.isException()
                            || response.getShortData() == null
                            || response.getShortData().length < REG_CORE_COUNT) {
                        log.warn("SMS8240Device " + getId() + " 核心块读取失败: "
                                + (response == null ? "无响应" : response.getExceptionMessage()));
                        setAllMalfunction();
                        return false;
                    }
                    return applyRegisters(response.getShortData(), false);
                })
                .exceptionally(ex -> {
                    log.warn("SMS8240Device " + getId() + " 核心块读取异常: " + ex.getMessage());
                    setAllMalfunction();
                    return false;
                });
    }

    /**
     * 解析并更新属性；供单测直接注入寄存器数据。
     *
     * @param regs     寄存器原始字（{@code big-endian ABCD}）
     * @param extended 是否包含原始标况扩展块（长度 ≥ 19）
     * @return true 表示解析成功并已提交状态
     */
    boolean applyRegisters(short[] regs, boolean extended) {
        if (regs == null || regs.length < REG_CORE_COUNT) {
            setAllMalfunction();
            return false;
        }
        try {
            int pm10Pipe = regs[IDX_PM10_PIPE_STATE] & 0xFFFF;
            int pm25Pipe = regs[IDX_PM25_PIPE_STATE] & 0xFFFF;
            int pm10Status = regs[IDX_PM10_COLLECT_STATUS] & 0xFFFF;
            int pm25Status = regs[IDX_PM25_COLLECT_STATUS] & 0xFFFF;

            // 枚举值严格：管路 / 采集状态只认 0/1；其他值视为协议异常，整轮按通信故障处理
            if (pm10Pipe > 1 || pm25Pipe > 1 || pm10Status > 1 || pm25Status > 1) {
                log.error("SMS8240Device " + getId() + " 协议异常寄存器值: pipe=(" + pm10Pipe + ","
                        + pm25Pipe + "), collectStatus=(" + pm10Status + "," + pm25Status + ")");
                setAllMalfunction();
                return false;
            }

            AttributeStatus ch1Status = pm10Status == 1 ? AttributeStatus.NORMAL : AttributeStatus.ALARM;
            AttributeStatus ch2Status = pm25Status == 1 ? AttributeStatus.NORMAL : AttributeStatus.ALARM;

            updateFloat(A_PM10_CORRECTED_STD_FLOW, regs, IDX_PM10_CORRECTED_STD_FLOW, ch1Status);
            updateFloat(A_PM10_WORKING_FLOW, regs, IDX_PM10_WORKING_FLOW, ch1Status);
            updateFloat(A_PM25_CORRECTED_STD_FLOW, regs, IDX_PM25_CORRECTED_STD_FLOW, ch2Status);
            updateFloat(A_PM25_WORKING_FLOW, regs, IDX_PM25_WORKING_FLOW, ch2Status);

            // 扩展块不可用时原始标况保持空白（不填 0、不报警）
            if (extended && regs.length >= REG_EXT_COUNT) {
                updateFloat(A_PM10_RAW_STD_FLOW, regs, IDX_PM10_RAW_STD_FLOW, ch1Status);
                updateFloat(A_PM25_RAW_STD_FLOW, regs, IDX_PM25_RAW_STD_FLOW, ch2Status);
            }

            updateU16(A_PM10_PIPE_STATE, pm10Pipe, AttributeStatus.NORMAL);
            updateU16(A_PM25_PIPE_STATE, pm25Pipe, AttributeStatus.NORMAL);
            updateU16(A_PM10_TARGET_ADDRESS, regs[IDX_PM10_TARGET_ADDRESS] & 0xFFFF, AttributeStatus.NORMAL);
            updateU16(A_PM25_TARGET_ADDRESS, regs[IDX_PM25_TARGET_ADDRESS] & 0xFFFF, AttributeStatus.NORMAL);
            updateU16(A_PM10_COLLECT_STATUS, pm10Status, AttributeStatus.NORMAL);
            updateU16(A_PM25_COLLECT_STATUS, pm25Status, AttributeStatus.NORMAL);
            updateU16(A_BOARD_SLAVE_ADDRESS, regs[IDX_BOARD_SLAVE_ADDRESS] & 0xFFFF, AttributeStatus.NORMAL);

            // 任一路采集成功即视为在测量；两路都失败为 ALARM
            this.deviceStatus = (pm10Status == 1 || pm25Status == 1) ? DeviceStatus.MEASURE : DeviceStatus.ALARM;

            commitPollState();
            return true;
        } catch (Exception e) {
            log.error("SMS8240Device " + getId() + " 解析异常", e);
            setAllMalfunction();
            return false;
        }
    }

    private void updateFloat(String id, short[] regs, int wordIndex, AttributeStatus status) {
        AttributeAbility<?> attr = getAttrs().get(id);
        if (attr instanceof ModbusFloatAttribute) {
            float value = ENDIAN.shortsToFloat(regs[wordIndex], regs[wordIndex + 1]);
            ((ModbusFloatAttribute) attr).updateValue(value, status);
        }
    }

    private void updateU16(String id, int value, AttributeStatus status) {
        AttributeAbility<?> attr = getAttrs().get(id);
        if (attr instanceof ModbusUnsignedShortAttribute) {
            ((ModbusUnsignedShortAttribute) attr).updateValue((short) value, status);
        }
    }

    // ==================== 启动回正 ====================

    private void restoreOpenBestEffort() {
        for (String id : new String[]{A_PM10_PIPE_STATE, A_PM25_PIPE_STATE}) {
            AttributeAbility<?> attr = getAttrs().get(id);
            if (!(attr instanceof ConfirmedPipeControlAttribute)) {
                continue;
            }
            ConfirmedPipeControlAttribute pipe = (ConfirmedPipeControlAttribute) attr;
            if (pipe.currentState() == ConfirmedPipeControlAttribute.OPEN) {
                continue;
            }
            pipe.applyState(ConfirmedPipeControlAttribute.OPEN).whenComplete((ok, ex) -> {
                if (ex != null || Boolean.FALSE.equals(ok)) {
                    log.warn("SMS8240Device " + getId() + " 启动回正失败(" + id + "): "
                            + (ex != null ? ex.getMessage() : "设备拒绝"));
                } else {
                    log.info("SMS8240Device " + getId() + " 启动回正贯通(" + id + ")");
                }
            });
        }
    }

    private void setAllMalfunction() {
        getAttrs().values().forEach(a -> a.setStatus(AttributeStatus.MALFUNCTION));
        this.deviceStatus = DeviceStatus.ALARM;
        // 通讯失败不得刷新 lastUpdated（online 依赖超时判离线）
    }

    // ==================== 配置解析 ====================

    /**
     * 解析 {@code flow_unit}：优先顶层键，兼容 {@code device_settings.flow_unit}；
     * 白名单只接受 {@link LiterFlowUnit} 枚举名，缺键/非法值回退 {@code L/min} 并 warn。
     */
    LiterFlowUnit resolveFlowUnit(Map<String, Object> config) {
        Object raw = config == null ? null : config.get("flow_unit");
        if (raw == null && config != null && config.get("device_settings") instanceof Map) {
            raw = ((Map<?, ?>) config.get("device_settings")).get("flow_unit");
        }
        if (raw == null) {
            return LiterFlowUnit.L_PER_MINUTE;
        }
        LiterFlowUnit parsed = parseFlowUnit(raw);
        if (parsed == null) {
            log.warn("SMS8240Device " + getId() + " flow_unit 非法('" + raw
                    + "')，回退 L/min；可选值: " + java.util.Arrays.toString(LiterFlowUnit.values()));
            return LiterFlowUnit.L_PER_MINUTE;
        }
        return parsed;
    }

    /**
     * 解析流量单位：同时接受单位符号（{@code L/min}、{@code m³/h}，即
     * {@link LiterFlowUnit#getName()}）与枚举名（{@code L_PER_MINUTE}，忽略大小写）。
     * 二者都匹配不到返回 null（由调用方回退并告警），不做模糊猜测。
     */
    static LiterFlowUnit parseFlowUnit(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        for (LiterFlowUnit unit : LiterFlowUnit.values()) {
            if (unit.getName().equalsIgnoreCase(text) || unit.name().equalsIgnoreCase(text)) {
                return unit;
            }
        }
        return null;
    }

    private boolean resolveRestoreOpenOnStart(Map<String, Object> config) {
        Object raw = config == null ? null : config.get("restore_open_on_start");
        if (raw == null) {
            return true;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        String text = raw.toString().trim();
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        log.warn("SMS8240Device " + getId() + " restore_open_on_start 非法('" + raw + "')，按 true 处理");
        return true;
    }
}
