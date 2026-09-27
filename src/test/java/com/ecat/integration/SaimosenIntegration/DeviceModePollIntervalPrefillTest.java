package com.ecat.integration.SaimosenIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.NumericConfigItem;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.integration.SaimosenIntegration.ConfigFlows.SaimosenConfigFlow;

/**
 * 采集间隔（poll_interval_sec）表单字段位置与分档预填契约：
 * <ol>
 *   <li>字段位于 device_mode_config 步（选完机型分档可见），device_config 步不再出现——
 *       首步渲染时 model 未知无法分档，字段留在首步只能统一预填 5，与 SMS8700 出厂 10s 档冲突；</li>
 *   <li>分档预填：SMS8700（air.monitor.pm 类下唯一型号）→ 出厂档 10；多型号气态类
 *       （如 air.monitor.co）全部机型均为 base 缺省档 → 通用默认 5；</li>
 *   <li>分档取值单一源：表单预填、SMS8700PMDevice.load() 出厂档回退共用
 *       SmsDeviceBase.defaultPollIntervalSec(model)，全仓不出现第二份 10/5 字面量；</li>
 *   <li>表单所见=运行时所得：device_mode_config 提交后 poll_interval_sec 落 entryData 顶层键
 *       （SMS8700PMDevice.load 的运行时读端不变）。</li>
 * </ol>
 * 驱动方式：真实 handleStep 走向导步序，断言 SHOW_FORM 结果携带的 schema（与前端渲染同源）；
 * 确定性同步：纯内存断言，无异步等待。
 */
public class DeviceModePollIntervalPrefillTest {

    /** 从 schema 中取出 poll_interval_sec 字段定义（前端渲染同源），不存在返回 null。 */
    private static NumericConfigItem pollField(ConfigSchema schema) {
        for (AbstractConfigItem<?> field : schema.getFields()) {
            if ("poll_interval_sec".equals(field.getKey())) {
                return (NumericConfigItem) field;
            }
        }
        return null;
    }

    /** 驱动向导：user 欢迎 → device_basic（class+sn）→ 返回 device_mode_config 步 SHOW_FORM 结果。 */
    private ConfigFlowResult driveToDeviceModeConfig(String deviceClass) {
        SaimosenConfigFlow flow = new SaimosenConfigFlow();
        flow.setContext(new FlowContext("test-poll-prefill-flow"));
        flow.handleStep("user", null);
        Map<String, Object> userAck = new HashMap<>();
        userAck.put("welcome", "start");
        flow.handleStep("user", userAck);
        Map<String, Object> basicInput = new HashMap<>();
        basicInput.put("class", deviceClass);
        basicInput.put("sn", "SN-PREFILL-001");
        return flow.handleStep("device_config", basicInput);
    }

    @Test
    public void deviceConfigStep_schemaNoLongerContainsPollIntervalField() {
        SaimosenConfigFlow flow = new SaimosenConfigFlow();
        flow.setContext(new FlowContext("test-poll-prefill-flow"));
        flow.handleStep("user", null);
        Map<String, Object> userAck = new HashMap<>();
        userAck.put("welcome", "start");
        // 提交 user 入口步返回的是 device_config 步表单，断言其 schema
        ConfigFlowResult basicStep = flow.handleStep("user", userAck);
        assertEquals("user 入口提交后应显示 device_config 步", "device_config", basicStep.getStepId());
        assertNull("采集间隔字段已挪至型号选择步，device_config 步 schema 不再包含该字段",
                pollField(basicStep.getSchema()));
    }

    @Test
    public void deviceModeStep_sms8700PrefillsFactoryTenSeconds() {
        ConfigFlowResult result = driveToDeviceModeConfig("air.monitor.pm");
        NumericConfigItem poll = pollField(result.getSchema());
        assertNotNull("device_mode_config 步必须包含采集间隔字段", poll);
        assertEquals("SMS8700 预填出厂档 10 秒",
                Double.valueOf(SMS8700PMDevice.FACTORY_POLL_INTERVAL_SEC), poll.getDefaultValue());
    }

    @Test
    public void deviceModeStep_gasAnalyzerPrefillsGenericFiveSeconds() {
        ConfigFlowResult result = driveToDeviceModeConfig("air.monitor.co");
        NumericConfigItem poll = pollField(result.getSchema());
        assertNotNull("device_mode_config 步必须包含采集间隔字段", poll);
        assertEquals("多型号气态类全部机型为 base 缺省档，预填通用默认 5 秒",
                Double.valueOf(SmsDeviceBase.DEFAULT_POLL_INTERVAL_SEC), poll.getDefaultValue());
    }

    @Test
    public void tierResolver_isTheOnlyTierSource() {
        assertEquals("分档解析器 SMS8700 档必须等于出厂档常量",
                SMS8700PMDevice.FACTORY_POLL_INTERVAL_SEC,
                SmsDeviceBase.defaultPollIntervalSec(SMS8700PMDevice.MODEL));
        assertEquals("SMS8700 分档为 10 秒", 10, SmsDeviceBase.defaultPollIntervalSec("SMS8700"));
        assertEquals("非 SMS8700 机型走 base 缺省档",
                SmsDeviceBase.DEFAULT_POLL_INTERVAL_SEC, SmsDeviceBase.defaultPollIntervalSec("SMS8500"));
        assertEquals("型号未定（多型号类渲染期）走 base 缺省档",
                SmsDeviceBase.DEFAULT_POLL_INTERVAL_SEC, SmsDeviceBase.defaultPollIntervalSec(null));
    }

    @Test
    public void deviceModeSubmit_pollIntervalSecLandsInEntryDataTopLevelKey() {
        SaimosenConfigFlow flow = new SaimosenConfigFlow();
        flow.setContext(new FlowContext("test-poll-submit-flow"));
        flow.handleStep("user", null);
        Map<String, Object> userAck = new HashMap<>();
        userAck.put("welcome", "start");
        flow.handleStep("user", userAck);
        Map<String, Object> basicInput = new HashMap<>();
        basicInput.put("class", "air.monitor.pm");
        basicInput.put("sn", "SN-PREFILL-002");
        flow.handleStep("device_config", basicInput);

        Map<String, Object> modeInput = new HashMap<>();
        modeInput.put("model", "SMS8700");
        modeInput.put("name", "预填回归颗粒物仪");
        modeInput.put("poll_interval_sec", 10);
        ConfigFlowResult result = flow.handleStep("device_mode_config", modeInput);

        assertEquals("提交后进入协议选择步", "protocol_select", result.getStepId());
        assertEquals("poll_interval_sec 必须落 entryData 顶层键（运行时 load 读端不变）",
                10, result.getContext().getEntryData("poll_interval_sec"));
    }
}
