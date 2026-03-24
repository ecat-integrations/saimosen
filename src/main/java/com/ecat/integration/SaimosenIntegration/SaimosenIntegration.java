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
}
