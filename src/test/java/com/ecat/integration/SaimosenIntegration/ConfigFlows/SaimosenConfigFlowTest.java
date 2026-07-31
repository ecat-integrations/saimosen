/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.SaimosenIntegration.ConfigFlows;

import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.ConfigFlow.ConfigItem.AbstractConfigItem;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.FlowContext;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * SaimosenConfigFlow 的 SN 必填 + reconfigure 只读 + 删除 randomUUID fallback 单测。
 * <p>背景：reconfigure 时 SN 空回显 → 用户看不到当前 SN → 误填新值 → uniqueId 漂移被
 * Part1 不可变护栏拒绝（共性 bug，bug-record-20260723-145304）。
 * <p>根治方案（docs/2026-07-23-config-flow-sn-required-readonly-design.md）：
 * <ul>
 *   <li>新建 SN 必填 → stepData.sn 有确定值 → 无需 randomUUID → stepData/entryData 不分叉</li>
 *   <li>reconfigure SN 只读 → 回显确定值、不可改 → uniqueId 不漂移</li>
 *   <li>删 randomUUID fallback → 空 SN 严格抛异常（不再随机兜底）</li>
 *   <li>import payload 空 SN → ABORT（堵 web schema 外的创建入口）</li>
 * </ul>
 *
 * @author coffee
 */
public class SaimosenConfigFlowTest {

    private SaimosenConfigFlow flow;
    private FlowContext ctx;

    @Before
    public void setUp() {
        flow = new SaimosenConfigFlow();
        ctx = flow.getContext();
        ctx.setCoordinate("com.ecat:integration-saimosen");
    }

    /** 从 schema 定位指定 key 的字段 */
    private AbstractConfigItem<?> findField(ConfigSchema schema, String key) {
        for (AbstractConfigItem<?> f : schema.getFields()) {
            if (key.equals(f.getKey())) {
                return f;
            }
        }
        throw new AssertionError("schema 中未找到字段: " + key);
    }

    /** 反射调 private generateUniqueId（与 DavisIntegrationTest 同手法） */
    private String generateUniqueIdReflect() throws Exception {
        Method m = SaimosenConfigFlow.class.getDeclaredMethod("generateUniqueId");
        m.setAccessible(true);
        return (String) m.invoke(flow);
    }

    private Map<String, Object> input(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ===== SN 必填（新建时可写） =====

    @Test
    public void testSn_Required_NewBuild() {
        // 新建模式走到 device_config：SN 必填，消除「空 SN → randomUUID → stepData/entryData 分叉」的源头
        ConfigFlowResult result = flow.executeUserStep(input("welcome", "ok"));
        assertEquals("应落到 device_config", "device_config", result.getStepId());
        AbstractConfigItem<?> sn = findField(result.getSchema(), "sn");
        assertTrue("新建时 SN 应必填（uniqueId 决定字段，不能留空）", sn.isRequired());
    }

    @Test
    public void testSn_Editable_NewBuild() {
        ConfigFlowResult result = flow.executeUserStep(input("welcome", "ok"));
        AbstractConfigItem<?> sn = findField(result.getSchema(), "sn");
        assertFalse("新建时 SN 应可写", sn.isReadOnly());
    }

    // ===== reconfigure 时 SN 只读 =====

    @Test
    public void testSn_ReadOnly_Reconfigure() {
        // reconfigure 模式：SN 只读（身份不可变，防止误改导致 uniqueId 漂移）
        ConfigFlowResult result = flow.executeReconfigureStep("entry-1", input("reconfigure_info", "ok"));
        assertEquals("reconfigure 应为 RECONFIGURE 模式", SourceType.RECONFIGURE, flow.getSourceType());
        assertEquals("应落到 device_config", "device_config", result.getStepId());
        AbstractConfigItem<?> sn = findField(result.getSchema(), "sn");
        assertTrue("reconfigure 时 SN 应只读", sn.isReadOnly());
    }

    // ===== generateUniqueId：用必填 SN 直接拼接，无随机兜底 =====

    @Test
    public void testGenerateUniqueId_WithSn_NoAutoPrefix() throws Exception {
        ctx.getEntryData().put("class", "air.monitor.so2");
        ctx.getEntryData().put("sn", "SO2-SN001");
        String uid = generateUniqueIdReflect();
        assertEquals("uniqueId = vendor_class_sn", "saimosen_air.monitor.so2_SO2-SN001", uid);
        assertFalse("有 SN 时 uniqueId 不应带 AUTO- 随机前缀", uid.contains("AUTO-"));
    }

    // ===== setEntryUniqueId 尽早：device_config 提交 SN 即确定 uniqueId 并排重 =====

    @Test
    public void testDeviceConfig_SetsUniqueIdImmediately() {
        // 原则：setEntryUniqueId 越早越好——提交 SN 的 step(device_config) 即确定 uniqueId 并排重，
        // 不等 final_confirm，重名冲突第一时间暴露，避免用户白填后续配置。
        flow.executeUserStep(input("welcome", "ok"));
        flow.handleStep("device_config",
            input("class_type_label", "说明", "class", "air.monitor.so2", "sn", "SO2-EARLY"));
        assertEquals("device_config 提交即应 setEntryUniqueId（尽早排重）",
            "saimosen_air.monitor.so2_SO2-EARLY", ctx.getEntryUniqueId());
    }

    // ===== import payload 校验 SN 非空 =====

    // testImportFlow_EmptySn_Aborts 已移除：IMPORT_FLOW handler 删除（P2.1），该入口不再存在。
}
