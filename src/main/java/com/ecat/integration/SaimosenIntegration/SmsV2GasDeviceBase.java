package com.ecat.integration.SaimosenIntegration;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.EcatCore;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.StringSelectAttribute;

import java.util.Arrays;
import java.util.Map;

/**
 * SMS8000 V2 四气态串口设备基类。手动状态属性与设备状态优先级对齐先河 XH*2000BV2。
 */
public abstract class SmsV2GasDeviceBase extends SerialDeviceBase {

    /** 轮询周期（毫秒）。生产默认=标准采集频率 5s（可经顶层键 poll_interval_sec 配置），load() 覆写。 */
    protected long pollIntervalMs = SmsDeviceBase.DEFAULT_POLL_INTERVAL_SEC * 1000L;

    public SmsV2GasDeviceBase(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public void load(EcatCore core) {
        super.load(core);
        // 采集间隔（顶层键 poll_interval_sec，秒）：缺省/非法（非数字、<0.01、>60）一律回退
        // DEFAULT_POLL_INTERVAL_SEC——配置面外越界值（手工构造/异构导入）的唯一防线；
        // 解析与秒→ms 换算复用 SmsDeviceBase.resolvePollIntervalMs（全仓只此一份），
        // 经 pollIntervalMs 下发 SerialPolling 节律（protected 字段，单测可注入短周期）
        pollIntervalMs = SmsDeviceBase.resolvePollIntervalMs(config, SmsDeviceBase.DEFAULT_POLL_INTERVAL_SEC);
    }

    @Override
    protected DeviceStatus computeDeviceStatus() {
        DeviceStatus manualStatus = checkManualStatus();
        if (manualStatus != null) {
            return manualStatus;
        }
        return super.computeDeviceStatus();
    }

    private DeviceStatus checkManualStatus() {
        for (AttributeBase<?> attr : getAttrs().values()) {
            String attrId = attr.getAttributeID();
            if (attr instanceof com.ecat.core.State.SelectAttribute) {
                if (attrId.contains("manual_status") || attrId.equals("work_status")) {
                    Object option = ((com.ecat.core.State.SelectAttribute<?>) attr).getCurrentOption();
                    if (option != null) {
                        String value = option.toString();
                        if (!"Normal".equals(value) && !"Auto".equals(value) && !"自动".equals(value)) {
                            AttributeStatus attrStatus = AttributeStatus.getEnum(value);
                            if (attrStatus != null && attrStatus != AttributeStatus.NORMAL && attrStatus != AttributeStatus.EMPTY) {
                                return mapAttributeStatusToDeviceStatus(attrStatus);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

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
        ((StringSelectAttribute) getAttrs().get(statusPrefix + "_manual_status"))
                .updateValue(AttributeStatus.NORMAL.getName(), AttributeStatus.NORMAL);

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
        ((StringSelectAttribute) getAttrs().get(statusPrefix + "_status"))
                .updateValue(AttributeStatus.NORMAL.getName(), AttributeStatus.NORMAL);
    }
}
