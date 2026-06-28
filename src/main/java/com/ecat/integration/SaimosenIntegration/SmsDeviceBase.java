package com.ecat.integration.SaimosenIntegration;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.EcatCore;
import com.ecat.integration.ModbusIntegration.ModbusInfo;
import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.BinaryAttribute;
import com.ecat.core.State.StringSelectAttribute;
import com.ecat.integration.ModbusIntegration.ModbusSerialInfo;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTcpInfo;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;
import com.ecat.integration.ModbusIntegration.Const;

/**
 * Abstract base class for devices that integrate with the Saimosen system.
 * This class extends the {@code DeviceBase} class and provides functionality
 * for integrating with Modbus RTU and Modbus TCP devices.
 *
 * <p>Key responsibilities of this class include:
 * <ul>
 *   <li>Loading Modbus integration and communication settings from ConfigEntry.</li>
 *   <li>Supporting both Modbus RTU (RS485) and Modbus TCP protocols.</li>
 *   <li>Initializing a Modbus source for communication with the device.</li>
 * </ul>
 *
 * <p>Configuration (from ConfigEntry data):
 * <ul>
 *   <li>{@code modbus_protocol} - Protocol type: "RTU" or "TCP"</li>
 *   <li>{@code comm_settings} - Communication settings (format depends on protocol):
 *     <ul>
 *       <li>RTU: serial_settings (serial_port, baudrate, data_bits, stop_bits, parity), slave_id</li>
 *       <li>TCP: ip_address, port, slave_id, timeout, tcp_protocol</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @see DeviceBase
 * @see ModbusIntegration
 *
 * @author coffee
 */
public abstract class SmsDeviceBase extends DeviceBase {
    protected static ModbusIntegration modbusIntegration;
    protected ModbusSource modbusSource;
    protected ModbusInfo modbusInfo;
    protected String modbusProtocol;

    protected ScheduledFuture<?> readFuture;

    /**
     * 从 ConfigEntry 构建设备（推荐）
     *
     * @param entry 配置条目
     */
    public SmsDeviceBase(ConfigEntry entry) {
        super(entry);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void load(EcatCore core) {
        super.load(core);
        if (modbusIntegration == null) {
            modbusIntegration = (ModbusIntegration) core.getIntegrationRegistry().getIntegration("integration-modbus");
        }
        if (modbusIntegration == null) {
            throw new RuntimeException("Modbus integration not found");
        }

        modbusProtocol = (String) config.getOrDefault("modbus_protocol", "RTU");

        Map<String, Object> commSettings = (Map<String, Object>) config.get("comm_settings");

        if ("TCP".equals(modbusProtocol)) {
            parseTcpCommSettings(commSettings);
        } else {
            // 默认 RTU
            parseRtuCommSettings(commSettings);
        }
    }

    /**
     * 解析 RTU 通讯设置
     * <p>
     * RTU 模式下 comm_settings 包含嵌套的 serial_settings 和 slave_id，
     * 字段名称遵循 ModbusRtuCommConfigSchema 的规范：
     * serial_port, baudrate, data_bits, stop_bits, parity
     */
    @SuppressWarnings("unchecked")
    private void parseRtuCommSettings(Map<String, Object> commSettings) {
        Map<String, Object> serialSettings = (Map<String, Object>) commSettings.get("serial_settings");

        String portName = (String) serialSettings.get("serial_port");
        Integer baudRate = parseNumeric(serialSettings, "baudrate", 9600);
        Integer dataBits = parseNumeric(serialSettings, "data_bits", 8);
        Integer stopBits = parseNumeric(serialSettings, "stop_bits", 1);
        Integer timeout = parseNumeric(serialSettings, "timeout", 2000);
        Integer slaveId = parseNumeric(commSettings, "slave_id", 1);

        // 解析校验位（新 Schema 使用 NONE/ODD/EVEN，兼容旧值 N/E/O）
        int parity;
        String parityStr = (String) serialSettings.getOrDefault("parity", "NONE");
        switch (parityStr.toUpperCase()) {
            case "NONE":
            case "N":
                parity = ModbusSerialInfo.NO_PARITY;
                break;
            case "ODD":
            case "O":
                parity = ModbusSerialInfo.ODD_PARITY;
                break;
            case "EVEN":
            case "E":
                parity = ModbusSerialInfo.EVEN_PARITY;
                break;
            case "MARK":
                parity = ModbusSerialInfo.MARK_PARITY;
                break;
            case "SPACE":
                parity = ModbusSerialInfo.SPACE_PARITY;
                break;
            default:
                parity = ModbusSerialInfo.NO_PARITY;
        }

        // 转换停止位（1 -> ONE_STOP_BIT, 2 -> TWO_STOP_BITS）
        int stopBitsValue;
        if (stopBits == 1) {
            stopBitsValue = ModbusSerialInfo.ONE_STOP_BIT;
        } else if (stopBits == 2) {
            stopBitsValue = ModbusSerialInfo.TWO_STOP_BITS;
        } else {
            stopBitsValue = ModbusSerialInfo.ONE_STOP_BIT;
        }

        modbusInfo = new ModbusSerialInfo(portName, baudRate, dataBits, stopBitsValue, parity, timeout, slaveId);
    }

    /**
     * 解析 TCP 通讯设置
     * <p>
     * TCP 模式下 comm_settings 包含 ip_address, port, slave_id, timeout, tcp_protocol
     */
    private void parseTcpCommSettings(Map<String, Object> commSettings) {
        String ipAddress = (String) commSettings.get("ip_address");
        int port = parseNumeric(commSettings, "port", 502);
        int slaveId = parseNumeric(commSettings, "slave_id", 1);
        String tcpProtocol = (String) commSettings.getOrDefault("tcp_protocol", "TCP");
        ModbusProtocol protocol = "RTU_OVER_TCP".equals(tcpProtocol)
            ? ModbusProtocol.RTU_OVER_TCP : ModbusProtocol.TCP;

        int timeout = parseNumeric(commSettings, "timeout", Const.DEFAULT_TCP_TIMEOUT_MS);
        modbusInfo = new ModbusTcpInfo(ipAddress, port, slaveId, protocol, timeout);
    }

    /**
     * 从 Map 中解析数值，支持 Number 和 String 类型
     */
    private int parseNumeric(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public void init() {
        // 如果 modbusSource 已经设置（测试场景），则跳过注册
        if (modbusSource == null) {
            modbusSource = modbusIntegration.register(modbusInfo, this.getClass().getName() + "-" + getId());
        }
    }

    @Override
    public void release() {
        if (modbusSource != null && modbusSource.isModbusOpen()) {
            modbusSource.closeModbus();
            log.info("Modbus closed for device " + getId() + ": " + modbusSource.getModbusInfo());
        }
    }

    /**
     * 此集成自行管理 deviceStatus 字段，跳过基类计算逻辑。
     */
    @Override
    public DeviceStatus getDeviceStatus() {
        return this.deviceStatus;
    }

    /**
     * 设置 ModbusSource（用于测试）
     */
    public void setModbusSource(ModbusSource modbusSource) {
        this.modbusSource = modbusSource;
    }

    /**
     * 为气体设备添加标准的手动状态属性
     * 子类在 createAttributes() 中调用此方法
     *
     * @param statusPrefix 状态属性前缀（如 "so2", "o3", "co", "nox"）
     */
    protected void addManualStatusAttributes(String statusPrefix) {
        java.util.List<String> manualStatusOptions = Arrays.asList(
            AttributeStatus.NORMAL.getName(),
            AttributeStatus.ALARM.getName(),
            AttributeStatus.MAINTENANCE.getName(),
            AttributeStatus.MALFUNCTION.getName(),
            AttributeStatus.CALIBRATION.getName(),
            AttributeStatus.ZERO_CHECK.getName(),
            AttributeStatus.SPAN_CHECK.getName(),
            AttributeStatus.ACCURACY_CHECK.getName(),
            AttributeStatus.ZERO_CALIBRATION.getName(),
            AttributeStatus.SPAN_CALIBRATION.getName(),
            AttributeStatus.FLOW_CHECK.getName(),
            AttributeStatus.QUALITY_CHECK.getName(),
            AttributeStatus.ZERO_DRIFT.getName(),
            AttributeStatus.SPAN_DRIFT.getName(),
            AttributeStatus.SPAN_REPRODUCIBILITY.getName(),
            AttributeStatus.MULTI_POINT_SPAN.getName(),
            AttributeStatus.PRECISION_CHECK.getName(),
            AttributeStatus.TEMP_PRESSURE_CALIBRATION.getName(),
            AttributeStatus.DEVICE_REPLACEMENT.getName()
        );
        setAttribute(new StringSelectAttribute(statusPrefix + "_manual_status", AttributeClass.STATUS, true, manualStatusOptions));
        ((StringSelectAttribute) getAttrs().get(statusPrefix + "_manual_status")).updateValue(AttributeStatus.NORMAL.getName(), AttributeStatus.NORMAL);

        java.util.List<String> allStatusOptions = Arrays.asList(
            AttributeStatus.NORMAL.getName(),
            AttributeStatus.ALARM.getName(),
            AttributeStatus.CALIBRATION.getName(),
            AttributeStatus.QUALITY_CHECK.getName(),
            AttributeStatus.WAITING.getName(),
            AttributeStatus.MAINTENANCE.getName(),
            AttributeStatus.MALFUNCTION.getName(),
            AttributeStatus.ZERO_CHECK.getName(),
            AttributeStatus.SPAN_CHECK.getName(),
            AttributeStatus.ACCURACY_CHECK.getName(),
            AttributeStatus.ZERO_CALIBRATION.getName(),
            AttributeStatus.SPAN_CALIBRATION.getName(),
            AttributeStatus.FLOW_CHECK.getName(),
            AttributeStatus.ZERO_DRIFT.getName(),
            AttributeStatus.SPAN_DRIFT.getName(),
            AttributeStatus.SPAN_REPRODUCIBILITY.getName(),
            AttributeStatus.MULTI_POINT_SPAN.getName(),
            AttributeStatus.PRECISION_CHECK.getName(),
            AttributeStatus.TEMP_PRESSURE_CALIBRATION.getName(),
            AttributeStatus.DEVICE_REPLACEMENT.getName()
        );
        setAttribute(new StringSelectAttribute(statusPrefix + "_status", AttributeClass.STATUS, false, allStatusOptions));
        ((StringSelectAttribute) getAttrs().get(statusPrefix + "_status")).updateValue(AttributeStatus.NORMAL.getName(), AttributeStatus.NORMAL);
    }

    /**
     * 结合手动状态与仪器自动状态，确定最终属性状态
     * 状态优先级：手动设置状态 > 报警状态 > 仪器自动状态
     */
    protected AttributeStatus determineAttributeStatus(AttributeStatus autoStatus, String manualStatusAttrId, String alarmAttrId) {
        if (manualStatusAttrId != null) {
            AttributeBase<?> manualStatusAttr = getAttrs().get(manualStatusAttrId);
            if (manualStatusAttr != null && manualStatusAttr.getState().getValue() != null) {
                String manualStatusValue = (String) manualStatusAttr.getState().getValue();
                AttributeStatus manualStatus = AttributeStatus.getEnum(manualStatusValue);
                if (manualStatus != null && manualStatus != AttributeStatus.NORMAL) {
                    return manualStatus;
                }
            }
        }

        if (alarmAttrId != null) {
            AttributeBase<?> alarmAttr = getAttrs().get(alarmAttrId);
            if (alarmAttr instanceof BinaryAttribute) {
                BinaryAttribute binaryAlarmAttr = (BinaryAttribute) alarmAttr;
                if (binaryAlarmAttr.isOn()) {
                    return AttributeStatus.ALARM;
                }
            }
        }

        if (autoStatus != null && autoStatus != AttributeStatus.EMPTY) {
            return autoStatus;
        }
        return AttributeStatus.NORMAL;
    }

    /**
     * 更新只读状态属性，显示最终确定的状态
     */
    protected void updateReadonlyStatusAttribute(String statusAttrId, AttributeStatus status) {
        AttributeBase<?> statusAttr = getAttrs().get(statusAttrId);
        if (statusAttr instanceof StringSelectAttribute) {
            ((StringSelectAttribute) statusAttr).updateValue(status.getName());
        }
    }
}
