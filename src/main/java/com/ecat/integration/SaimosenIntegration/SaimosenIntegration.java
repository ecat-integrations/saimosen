package com.ecat.integration.SaimosenIntegration;

import java.util.Map;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceClasses;
import com.ecat.core.Integration.IntegrationDeviceBase;
import com.ecat.integration.SaimosenIntegration.ConfigFlows.SaimosenConfigFlow;
import com.ecat.integration.SaimosenIntegration.ConfigSchemas.SaimosenDeviceConfigSchema;

/**
 * The SaimosenIntegration class extends IntegrationDeviceBase and provides the implementation
 * for managing Saimosen devices through the ConfigFlow pattern.
 *
 * <p>Key Features:</p>
 * <ul>
 *   <li>Configuration via ConfigFlow with ConfigEntry-based device creation.</li>
 *   <li>Supports 9 device classes: Calibrator, QC, SmartPowerStabilizer, SampleTube,
 *       ParticulateZeroChecker, O3, NO2, CO, SO2.</li>
 *   <li>Lifecycle management delegated to parent class IntegrationDeviceBase.</li>
 * </ul>
 *
 * <p>Device Configuration (from ConfigEntry data):</p>
 * <ul>
 *   <li>class: Required, String, one of the 9 DeviceClasses values.</li>
 *   <li>name: Required, String, device display name.</li>
 *   <li>sn: Optional, String, serial number for uniqueId generation.</li>
 *   <li>vendor: Auto-filled as "saimosen".</li>
 *   <li>modbus_protocol: "RTU" or "TCP".</li>
 *   <li>comm_settings: Nested communication settings (format depends on protocol).</li>
 * </ul>
 *
 * @author coffee
 */
public class SaimosenIntegration extends IntegrationDeviceBase {

    @Override
    public AbstractConfigFlow getConfigFlow() {
        return new SaimosenConfigFlow();
    }

    @Override
    protected DeviceBase createDeviceFromEntry(ConfigEntry entry) {
        ConfigSchema schema = new SaimosenDeviceConfigSchema().createSchema();
        Map<String, Object> errors = schema.validate(entry.getData());
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Config validation failed: " + errors);
        }

        String deviceClass = (String) entry.getData().get("class");

        SmsDeviceBase device;

        try {
            DeviceClasses dc = DeviceClasses.getEnum(deviceClass);
            switch (dc) {
                case AIR_MONITOR_CALIBRATOR:
                    device = new CalibratorDevice(entry);
                    break;
                case AIR_MONITOR_QC:
                    device = new QCDevice(entry);
                    break;
                case POWER_SUPPLY_STABILIZER:
                    device = new SmartPowerStabilizer(entry);
                    break;
                case SAMPLE_TUBE:
                    device = new SampleTube(entry);
                    break;
                case AIR_MONITOR_PM_QC:
                    device = new ParticulateZeroChecker(entry);
                    break;
                case AIR_MONITOR_O3:
                    device = new O3Device(entry);
                    break;
                case AIR_MONITOR_NO2:
                    device = new NO2Device(entry);
                    break;
                case AIR_MONITOR_CO:
                    device = new CODevice(entry);
                    break;
                case AIR_MONITOR_SO2:
                    device = new SO2Device(entry);
                    break;
                default:
                    log.error("Device class {} is not supported", deviceClass);
                    return null;
            }
        } catch (Exception e) {
            log.error("Failed to parse device class: {}", deviceClass, e);
            return null;
        }

        device.load(core);
        device.init();
        return device;
    }

    /**
     * 根据设备类型 class 推导硬件型号。
     * <p>
     * ConfigFlow 和集成层共享此方法，统一维护 class→model 映射。
     *
     * @param deviceClass 设备类型标识（如 "air.monitor.so2"）
     * @return 硬件型号（如 "SMS8200"），未知类型返回 null
     */
    public static String classToModel(String deviceClass) {
        if (deviceClass == null) return null;
        switch (deviceClass) {
            case "air.monitor.so2": return "SMS8200";
            case "air.monitor.co": return "SMS8500";
            case "air.monitor.no2": return "SMS8300";
            case "air.monitor.o3": return "SMS8400";
            case "air.monitor.qc": return "SMS8910";
            case "air.monitor.pm.qc": return "SMS8220";
            case "air.monitor.calibrator": return "SMS8600";
            case "power.supply.stabilizer": return "IRP0501B";
            case "sample.tube": return "SMS6930";
            default: return null;
        }
    }
}
