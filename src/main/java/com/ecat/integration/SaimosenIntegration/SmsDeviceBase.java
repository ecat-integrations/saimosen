package com.ecat.integration.SaimosenIntegration;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.integration.ModbusIntegration.ModbusSerialInfo;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.fazecast.jSerialComm.SerialPort;
import com.ecat.integration.ModbusIntegration.ModbusIntegration;

/**
 * Abstract base class for devices that integrate with the MyESA system.
 * This class extends the `DeviceBase` class and provides functionality
 * for integrating with a Modbus TCP system using the `ModbusIntegration`.
 *
 * <p>Key responsibilities of this class include:
 * <ul>
 *   <li>Loading Modbus TCP integration and communication settings.</li>
 *   <li>Initializing a Modbus source for communication with the device.</li>
 * </ul>
 *
 * <p>Configuration requirements:
 * <ul>
 *   <li>The `config` map must include a `comm_settings` key containing:
 *     <ul>
 *       <li>`ip` (String): The IP address of the Modbus device.</li>
 *       <li>`port` (Integer): The port number for the Modbus connection.</li>
 *       <li>`slaveId` (Integer): The slave ID of the Modbus device.</li>
 *     </ul>
 *   </li>
 *   <li>The `config` map must also include an `id` key for uniquely identifying the device.</li>
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
    protected ModbusSerialInfo modbusInfo;

    protected ScheduledFuture<?> readFuture;

    public SmsDeviceBase(Map<String, Object> config) {
        super(config);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void load(EcatCore core) {
        super.load(core);
        if (modbusIntegration == null) {
            modbusIntegration = (ModbusIntegration) core.getIntegrationRegistry().getIntegration("integration-modbus");
        }

        Map<String, Object> commSettings = (Map<String, Object>) config.get("comm_settings");
        int parity;
        switch ((String)commSettings.get("parity")) {
            case "N":
                parity = SerialPort.NO_PARITY;
                break;
            case "E":
                parity = SerialPort.EVEN_PARITY;
                break;
            case "O":
                parity = SerialPort.ODD_PARITY;
                break;
            default:
                parity = SerialPort.NO_PARITY;
        }
        modbusInfo = new ModbusSerialInfo(
            (String) commSettings.get("port"),
            (Integer) commSettings.get("baudRate"),
            (Integer) commSettings.get("numDataBit"),
            (Integer) commSettings.get("numStopBit"),
            parity,
            (Integer) commSettings.getOrDefault("timeout", 2000),
            (Integer) commSettings.get("slaveId")
        );
    }

    @Override
    public void init() {
        // 如果 modbusSource 已经设置（测试场景），则跳过注册
        if (modbusSource == null) {
            modbusSource = modbusIntegration.register(modbusInfo, this.getClass().getName() + "-" + config.get("id"));
        }
    }

    @Override
    public void release() {
        if (modbusSource != null && modbusSource.isModbusOpen()) {
            modbusSource.closeModbus();
            log.info("Modbus closed for device " + config.get("id") + ": " + modbusSource.getModbusInfo());
        }
    }

    /**
     * 设置 ModbusSource（用于测试）
     */
    public void setModbusSource(ModbusSource modbusSource) {
        this.modbusSource = modbusSource;
    }
}
