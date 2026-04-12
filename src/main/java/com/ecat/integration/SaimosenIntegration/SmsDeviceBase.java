package com.ecat.integration.SaimosenIntegration;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.EcatCore;
import com.ecat.integration.ModbusIntegration.ModbusInfo;
import com.ecat.integration.ModbusIntegration.ModbusProtocol;
import com.ecat.integration.ModbusIntegration.ModbusSerialInfo;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.ModbusTcpInfo;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;

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

        modbusInfo = new ModbusTcpInfo(ipAddress, port, slaveId, protocol);
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
}
