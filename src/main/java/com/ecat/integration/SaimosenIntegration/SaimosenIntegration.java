package com.ecat.integration.SaimosenIntegration;

import java.util.*;
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
    // 定义class model映射关系protocol
    private static final Map<String, Map<String, String>> CLASS_MODEL_MAP;
    // model映射关系protocol
    private static final Map<String, String> MODEL_PROTOCOL_MAP;
    /**
     * 协议枚举（提取所有协议类型，杜绝字符串写错）
     */
    public enum Protocol {
        MODBUS,   // Modbus 协议
        SERIAL    // 串口协议
    }
    static {
        MODEL_PROTOCOL_MAP = new HashMap<>();
        CLASS_MODEL_MAP = new HashMap<>();
        // 二氧化硫监测仪
        Map<String, String> so2Map = new HashMap<>();
        so2Map.put("SMS8200", "SMS8200");
        MODEL_PROTOCOL_MAP.put("SMS8200", Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("air.monitor.so2", so2Map);
        // 一氧化碳监测仪
        Map<String, String> coMap = new HashMap<>();
        coMap.put("SMS8500", "SMS8500");
        MODEL_PROTOCOL_MAP.put("SMS8500", Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("air.monitor.co", coMap);
        // 二氧化氮监测仪
        Map<String, String> no2Map = new HashMap<>();
        no2Map.put("SMS8300", "SMS8300");
        MODEL_PROTOCOL_MAP.put("SMS8300", Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("air.monitor.no2", no2Map);
        // 臭氧监测仪
        Map<String, String> o3Map = new HashMap<>();
        o3Map.put("SMS8400", "SMS8400");
        MODEL_PROTOCOL_MAP.put("SMS8400", Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("air.monitor.o3", o3Map);
        // 质控仪：SMS8910 完整协议 V1 / V2
        Map<String, String> qcMap = new HashMap<>();
        qcMap.put(SaimosenQCModels.SMS8910, "SMS8910（完整协议 V1，寄存器 0~232）");
        MODEL_PROTOCOL_MAP.put(SaimosenQCModels.SMS8910, Protocol.MODBUS.name());
        qcMap.put(SaimosenQCModels.SMS8910V2, "SMS8910V2（完整协议 V2，寄存器 0~244）");
        MODEL_PROTOCOL_MAP.put(SaimosenQCModels.SMS8910V2, Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("air.monitor.qc", qcMap);
        // PM监测仪
        Map<String, String> pmQcMap = new HashMap<>();
        pmQcMap.put("SMS8220", "SMS8220");
        MODEL_PROTOCOL_MAP.put("SMS8220", Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("air.monitor.pm.qc", pmQcMap);
        // 校准仪（多型号示例）
        Map<String, String> calibratorMap = new HashMap<>();
        calibratorMap.put("SMS8600V1", "SMS8600V1");
        MODEL_PROTOCOL_MAP.put("SMS8600V1", Protocol.MODBUS.name());
        calibratorMap.put("SMS8600V2", "SMS8600V2");
        MODEL_PROTOCOL_MAP.put("SMS8600V2", Protocol.SERIAL.name());
        CLASS_MODEL_MAP.put("air.monitor.calibrator", calibratorMap);
        // 稳压电源
        Map<String, String> powerMap = new HashMap<>();
        powerMap.put("IRP0501B", "IRP0501B");
        MODEL_PROTOCOL_MAP.put("IRP0501B", Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("power.supply.stabilizer", powerMap);
        // 采样管
        Map<String, String> sampleMap = new HashMap<>();
        sampleMap.put("SMS6930", "SMS6930");
        MODEL_PROTOCOL_MAP.put("SMS6930", Protocol.MODBUS.name());
        CLASS_MODEL_MAP.put("sample.tube", sampleMap);
    }
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

        // 类型放宽到 DeviceBase：SMS8600V2Device 继承 SerialDeviceBase（与其它分支的 SmsDeviceBase 共同父类 DeviceBase），
        // 让 SMS8600V2 分支与其它分支一致——只负责构造，统一落到方法末尾的 load+init。
        // 工厂方法禁止越界 addDevice：注册（addDevice→getOrCreate 解析稳定 id + registry + persist）
        // 由基类 createEntry 统一收口，否则会双 publish(DEVICE_LIFECYCLE CREATE) + 双 persist。
        DeviceBase device = null;
        String model = (String) entry.getData().get("model");
        try {
            DeviceClasses dc = DeviceClasses.getEnum(deviceClass);
            switch (dc) {
                case AIR_MONITOR_CALIBRATOR:
                    if(model.equals("SMS8600V1")){
                        device = new CalibratorDevice(entry);
                    }
                    else if(model.equals("SMS8600V2")){
                        device = new SMS8600V2Device(entry);
                    }else{
                        device = new CalibratorDevice(entry);
                    }

                    break;
                case AIR_MONITOR_QC:
                    if (SaimosenQCModels.SMS8910V2.equals(model)) {
                        device = new QCV2Device(entry);
                    } else {
                        device = new QCDevice(entry);
                    }
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
        if(device != null){
            device.load(core);
            device.init();
        }
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
    /**
     * 根据设备类型code 获取 【设备型号-中文名】映射
     */
    public static Map<String, String> classToModelMap(String deviceClass) {
        if (deviceClass == null) {
            return Collections.emptyMap();
        }
        return CLASS_MODEL_MAP.getOrDefault(deviceClass, Collections.emptyMap());
    }

    /**
        根据mode获取protocol
    */
    public static String getProtocolByMode(String model){
        if(model == null){
            return null;
        }
        return MODEL_PROTOCOL_MAP.get(model);
    }

}
