package com.ecat.integration.SaimosenIntegration;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.BinaryAttribute;
import com.ecat.core.State.StringSelectAttribute;
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
            if (manualStatusAttr != null && manualStatusAttr.getValue() != null) {
                String manualStatusValue = (String) manualStatusAttr.getValue();
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
