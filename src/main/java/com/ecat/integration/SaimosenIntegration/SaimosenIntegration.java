package com.ecat.integration.SaimosenIntegration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Collections;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceClasses;
import com.ecat.core.Integration.IntegrationDeviceBase;
import com.ecat.core.Utils.DynamicConfig.*;

/**
 * The SaimosenIntegration class extends IntegrationDeviceBase and provides the implementation
 * for initializing, starting, pausing, releasing, and managing devices in the integration.
 * It also defines the configuration schema for devices and validates device configurations.
 *
 * <p>Key Features:</p>
 * <ul>
 *   <li>Initialization of the integration and loading of device configurations.</li>
 *   <li>Lifecycle management methods: onInit, onStart, onPause, and onRelease.</li>
 *   <li>Definition and validation of device configuration schema using ConfigDefinition.</li>
 *   <li>Dynamic creation of devices based on configuration.</li>
 * </ul>
 *
 * <p>Device Configuration Schema:</p>
 * <ul>
 *   <li>id: Required, String, length 1-50.</li>
 *   <li>name: Required, String, length 1-50.</li>
 *   <li>class: Optional, String, must be one of ["air.monitor.no2",....].</li>
 *   <li>sn: Optional, String, length 1-50.</li>
 *   <li>vendor: Optional, String, length 1-50.</li>
 *   <li>model: Optional, String, length 1-50.</li>
 *   <li>abilities: Optional, List<String>, size 0-3, values must be one of ["gas.switch", "gas.zero.generate", "gas.span.generate"].</li>
 *   <li>integration: Required, String, length 1-50.</li>
 *   <li>comm_settings: Required, Map, containing:
 *     <ul>
 *       <li>ip: Required, String, length 1-50.</li>
 *       <li>port: Required, Integer.</li>
 *       <li>slaveId: Required, Integer.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Device Creation:</p>
 * <ul>
 *   <li>Validates the configuration using the defined schema.</li>
 *   <li>Creates a device instance based on the "class" field in the configuration.</li>
 *   <li>Currently supports "air.monitor.no2" class, which creates an instance of NOXDevice.</li>
 * </ul>
 * 
 * @author coffee
 */
public class SaimosenIntegration extends IntegrationDeviceBase {

    @Override
    public void onInit() {
        Map<String, Object> integrationConfig = integrationManager.loadConfig(this.getName());

        // 获取 devices 列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deviceConfigs = (List<Map<String, Object>>) integrationConfig.getOrDefault("devices", null);
        if (deviceConfigs != null) {
            for (Map<String, Object> config : deviceConfigs) {
                createDevice(config);
            }
        }
    }

    @Override
    public void onStart() {
        log.info("SaimosenIntegration started");
        for (DeviceBase device : getAllDevices()) {
            device.start();
        }
    }

    @Override
    public void onPause() {
        log.info("SaimosenIntegration paused");
        for (DeviceBase device : getAllDevices()) {
            device.stop();
        }
    }

    @Override
    public void onRelease() {
        log.info("SaimosenIntegration released");
        for (DeviceBase device : getAllDevices()) {
            device.release();
        }
        devices.clear();
    }

    @Override
    public ConfigDefinition getDeviceConfigDefinition() {
        if (deviceConfigDefinition == null) {
            deviceConfigDefinition = new ConfigDefinition();

            StringLengthValidator lengthValidator = new StringLengthValidator(1, 50);
            Set<String> classValidValues = new HashSet<>(Arrays.asList(
                                                                DeviceClasses.AIR_MONITOR_CALIBRATOR.getClassName(),
                                                                DeviceClasses.AIR_MONITOR_QC.getClassName(),
                                                                DeviceClasses.POWER_SUPPLY_STABILIZER.getClassName(),
                                                                DeviceClasses.SAMPLE_TUBE.getClassName(),
                                                                DeviceClasses.AIR_MONITOR_PM_QC.getClassName(),
                                                                DeviceClasses.AIR_MONITOR_O3.getClassName(),
                                                                DeviceClasses.AIR_MONITOR_NO2.getClassName(),
                                                                DeviceClasses.AIR_MONITOR_CO.getClassName(),
                                                                DeviceClasses.AIR_MONITOR_SO2.getClassName()
                                                                ));
            StringEnumValidator classEnumValidator = new StringEnumValidator(classValidValues);

            ConfigItemBuilder builder = new ConfigItemBuilder()
                .add(new ConfigItem<>("id", String.class, true, null, Collections.singletonList(lengthValidator)))
                .add(new ConfigItem<>("name", String.class, true, null, Collections.singletonList(lengthValidator)))
                .add(new ConfigItem<>("class", String.class, false, null, classEnumValidator))
                .add(new ConfigItem<>("sn", String.class, false, null, Collections.singletonList(lengthValidator)))
                .add(new ConfigItem<>("vendor", String.class, false, null, Collections.singletonList(lengthValidator)))
                .add(new ConfigItem<>("model", String.class, false, null, Collections.singletonList(lengthValidator)))
                .add(new ConfigItem<>("comm_settings", Map.class, true, null)
                    .addNestedConfigItems(new ConfigItemBuilder()
                        .add(new ConfigItem<>("port", String.class, true, null, Collections.singletonList(lengthValidator)))
                        .add(new ConfigItem<>("baudRate", Integer.class, true, null))
                        .add(new ConfigItem<>("numDataBit", Integer.class, true, null))
                        .add(new ConfigItem<>("numStopBit", Integer.class, true, null))
                        .add(new ConfigItem<>("parity", String.class, true, null, Collections.singletonList(lengthValidator)))
                        .add(new ConfigItem<>("timeout", Integer.class, false, 1000))
                        .add(new ConfigItem<>("slaveId", Integer.class, true, null))
                    ));

            deviceConfigDefinition.define(builder);
        }
        return deviceConfigDefinition;
    }

    @Override
    public boolean createDevice(Map<String, Object> config) {
        boolean isValid = deviceConfigDefinition.validateConfig(config);
        if (isValid) {
            try {
                String deviceClass = (String) config.get("class");
                SmsDeviceBase device;
                DeviceClasses dc = DeviceClasses.getEnum(deviceClass);
                switch(dc){
                    case AIR_MONITOR_CALIBRATOR: 
                        device = new CalibratorDevice(config);
                        break;
                    case AIR_MONITOR_QC:
                        device = new QCDevice(config);
                        break;
                    case POWER_SUPPLY_STABILIZER:
                        device = new SmartPowerStabilizer(config);
                        break;
                    case SAMPLE_TUBE:
                        device = new SampleTube(config);
                        break;
                    case AIR_MONITOR_PM_QC:
                        device = new ParticulateZeroChecker(config);
                        break;
                    case AIR_MONITOR_O3:
                        device = new O3Device(config);
                        break;
                    case AIR_MONITOR_NO2:
                        device = new NO2Device(config);
                        break;
                    case AIR_MONITOR_CO:
                        device = new CODevice(config);
                        break;
                    case AIR_MONITOR_SO2:
                        device = new SO2Device(config);
                        break;
                    default:
                        return false;
                }

                device.load(core);
                device.init();
                addDevice(device);
                return true;
            } catch (Exception e) {
                log.error("Create device failed", e);
                return false;
            }
        }
        else{
            Map<ConfigItem<?>, String> invalidItems = deviceConfigDefinition.getInvalidConfigItems();
            for (Map.Entry<ConfigItem<?>, String> entry : invalidItems.entrySet()) {
                log.error("集成{}的配置项: {} 错误信息: {}.", this.getName(), entry.getKey().getKey(), entry.getValue());
            }
        }
        return false;
    }
}
