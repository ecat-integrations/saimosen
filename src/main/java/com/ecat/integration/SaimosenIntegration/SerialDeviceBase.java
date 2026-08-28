package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.EcatCore;
import com.ecat.core.State.*;
import com.ecat.integration.SerialIntegration.SerialInfo;
import com.ecat.integration.SerialIntegration.SerialIntegration;
import com.ecat.integration.SerialIntegration.SerialSource;
import com.fazecast.jSerialComm.SerialPort;

import java.util.Map;

import static org.apache.commons.lang3.math.NumberUtils.toInt;

/**
 * XH device bacs class
 * @author coffee
 */
public abstract class SerialDeviceBase extends DeviceBase {

    protected static SerialIntegration serialIntegration;
    // protected SerialPort serialPort;
    protected SerialSource serialSource;
    protected SerialInfo serialInfo;

    public SerialDeviceBase(ConfigEntry entry) {
        super(entry);
    }

    protected enum AttributeType {
        NUMERIC,
        TEXT,
        SELECT
    }

    @Override
    public void load(EcatCore core) {
        super.load(core);
        if(SerialDeviceBase.serialIntegration == null) {
            SerialDeviceBase.serialIntegration = (SerialIntegration) core.getIntegrationRegistry().getIntegration("integration-serial");
        }
        
        @SuppressWarnings("unchecked")
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
        // 流控 (value = SerialPort.FLOW_CONTROL_* 常量组合)
        int flowControl = toInt((String) commSettings.getOrDefault("flow_control", "0"), 0);

        // 超时时间（毫秒），默认 500
        int timeout = (int) commSettings.getOrDefault("timeout", 500);
        // public SerialInfo(String portName, Integer baudrate, Integer dataBits, Integer stopBits, Integer parity) {
        serialInfo = new SerialInfo(
            (String) commSettings.get("serial_port"),
            toInt((String) commSettings.get("baudrate")),
            toInt((String) commSettings.get("data_bits")),
            toInt((String) commSettings.get("stop_bits")),
            parity,
            flowControl,
            timeout
        );


    }

    @Override
    public void init() {
        serialSource = SerialDeviceBase.serialIntegration.register(serialInfo, this.getClass().getName());
    }
    @Override
    public void stop() {
        // 轮询收尾不经设备 stop：SerialPolling 内部 host.onRemove 绑定，
        // 框架 cancelManagedTasks LIFO sweep 接管（18 号设计 §3.3）
    }
    @Override
    public void release() {
        stop();
        if (serialSource != null) {
            try {
                serialSource.closePort();
                log.info("Serial port closed for device " + config.get("id") + ": " + serialSource.getSystemPortName());
            } catch (Exception e) {
                log.warn("Error closing serial port for device {}: {}", config.get("id"), e.getMessage());
            }
        }
    }

    protected String checkReadBuffer(String response) {
        if (response.endsWith("$")) {
            return response;
        }
        return null;
    }

    protected byte[] checkByteResponse(byte[] buffer) {
        // $ 对应的ASCII字节是 0x24，判断是否以 $ 结束
        if (buffer == null || buffer.length == 0) {
            return new byte[0];
        }

        // 获取最后一个字节，判断是否是 $
        byte endChar = 0x24; // $ 符号
        if (buffer[buffer.length - 1] == endChar) {
            // 以 $ 结尾，返回完整数据
            return buffer;
        } else {
            // 不是以 $ 结尾，返回空数组表示不完整
            return null;
        }
    }

    protected Boolean handleException(Throwable ex) {
        log.error("Response handling error: " + ex.getMessage());
        return false;
    }

    // 将AttributeStatus映射到DeviceStatus的方法
    protected DeviceStatus mapToDeviceStatus(AttributeStatus attrStatus) {
        switch (attrStatus) {
            case NORMAL:
                return DeviceStatus.NORMAL;
            case CALIBRATION:
            case ZERO_CALIBRATION:
            case SPAN_CALIBRATION:
                return DeviceStatus.CALIBRATION;
            case QUALITY_CHECK:
                return DeviceStatus.DIAGNOSTIC;
            case WAITING:
                return DeviceStatus.WARM_UP;
            case MAINTENANCE:
                return DeviceStatus.RECOVERY;
            default:
                return DeviceStatus.UNKNOWN;
        }
    }

    protected AttributeStatus getDataStatus(String manualStatusAttrId){
        AttributeStatus status = AttributeStatus.NORMAL;
        // 第一步：检查 manual_status（第一优先级）
        if (manualStatusAttrId != null) {
            AttributeBase<?> manualStatusAttr = getAttrs().get(manualStatusAttrId);
            // 从不可变 state 读，getValue 已封装为 protected
            if (manualStatusAttr != null && manualStatusAttr.getState() != null && manualStatusAttr.getState().getValue() != null) {
                String manualStatusValue = (String) manualStatusAttr.getState().getValue();
                AttributeStatus manualStatus = AttributeStatus.getEnum(manualStatusValue);
                if (manualStatus != null && manualStatus != AttributeStatus.NORMAL) {
                    status = manualStatus;
                    return status;  // 手动设置的状态优先级最高，直接返回
                }
            }
        }
        return status;
    }
    /**
     * 解析数据前缀状态，结合 manual_status 确定最终状态
     * 状态优先级：手动设置状态 > 报警状态 > 数据前缀状态
     *
     * @param valueStr 原始数据字符串（可能包含前缀 * # % !）
     * @param manualStatusAttrId manual_status 属性ID（如 "so2_manual_status"）
     * @param alarmAttrId 报警属性ID（如 "GeneralAlarm"），可为 null
     * @return 最终的状态
     */
    protected AttributeStatus determineDataStatus(String valueStr, String manualStatusAttrId, String alarmAttrId) {
        AttributeStatus status = AttributeStatus.NORMAL;

        // 第一步：检查 manual_status（第一优先级）
        if (manualStatusAttrId != null) {
            AttributeBase<?> manualStatusAttr = getAttrs().get(manualStatusAttrId);
            // 从不可变 state 读，getValue 已封装为 protected
            if (manualStatusAttr != null && manualStatusAttr.getState() != null && manualStatusAttr.getState().getValue() != null) {
                String manualStatusValue = (String) manualStatusAttr.getState().getValue();
                AttributeStatus manualStatus = AttributeStatus.getEnum(manualStatusValue);
                if (manualStatus != null && manualStatus != AttributeStatus.NORMAL) {
                    status = manualStatus;
                    return status;  // 手动设置的状态优先级最高，直接返回
                }
            }
        }

        // 第二步：检查报警状态（第二优先级）
        if (alarmAttrId != null) {
            AttributeBase<?> alarmAttr = getAttrs().get(alarmAttrId);
            if (alarmAttr != null && alarmAttr instanceof BinaryAttribute) {
                BinaryAttribute binaryAlarmAttr = (BinaryAttribute) alarmAttr;
                if (binaryAlarmAttr.isOn()) {
                    status = AttributeStatus.ALARM;
                    return status;  // 报警状态优先级第二，直接返回
                }
            }
        }

        // 第三步：检查数据前缀状态（第三优先级）
        if (valueStr.startsWith("*")) {
            status = AttributeStatus.CALIBRATION;
        } else if (valueStr.startsWith("%")) {
            status = AttributeStatus.QUALITY_CHECK;
        } else if (valueStr.startsWith("!")) {
            status = AttributeStatus.WAITING;
        } else if (valueStr.startsWith("#")) {
            status = AttributeStatus.MAINTENANCE;
        }

        return status;
    }

    /**
     * 更新只读状态属性，显示最终确定的状态
     *
     * @param statusAttrId 只读状态属性ID（如 "so2_status"）
     * @param status 确定的状态
     */
    protected void updateReadonlyStatusAttribute(String statusAttrId, AttributeStatus status) {
        AttributeBase<?> statusAttr = getAttrs().get(statusAttrId);
        if (statusAttr != null && statusAttr instanceof StringSelectAttribute) {
            ((StringSelectAttribute) statusAttr).updateValue(status.getName());
        }
    }
}
