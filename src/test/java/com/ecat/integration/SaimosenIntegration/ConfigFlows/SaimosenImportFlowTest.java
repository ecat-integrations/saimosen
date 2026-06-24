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
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.ConfigFlow.ImportFlowPayload;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SaimosenConfigFlow 的 IMPORT_FLOW 发现入口单测。
 * <p>验证：外部部分识别信息 → 跳过型号选择步、预填 entryData、直达连接配置区（protocol_select），
 * 以及格式/型号不支持时严格 ABORT。
 *
 * @author coffee
 */
public class SaimosenImportFlowTest {

    private SaimosenConfigFlow flow;
    private FlowContext ctx;

    @Before
    public void setUp() {
        flow = new SaimosenConfigFlow();
        ctx = flow.getContext();
        ctx.setCoordinate("com.ecat:integration-saimosen");
    }

    @Test
    public void testImportFlow_SO2_LandsOnProtocolSelect() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-saimosen", 1,
                "air.monitor.so2|SMS8200|SO2-SN001|测试SO2");

        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("SO2(Modbus) 应直达 protocol_select（跳过型号选择步）", "protocol_select", result.getStepId());
        assertEquals("sourceType 应为 IMPORT_FLOW", SourceType.IMPORT_FLOW, flow.getSourceType());
        // 预填 entryData
        assertEquals("class 应预填", "air.monitor.so2", ctx.getEntryData("class"));
        assertEquals("model 应预填", "SMS8200", ctx.getEntryData("model"));
        assertEquals("sn 应预填", "SO2-SN001", ctx.getEntryData("sn"));
        assertEquals("name 应预填", "测试SO2", ctx.getEntryData("name"));
        assertEquals("vendor 应预填", "saimosen", ctx.getEntryData("vendor"));
    }

    @Test
    public void testImportFlow_NameOmitted_UsesDefault() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-saimosen", 1,
                "air.monitor.so2|SMS8200|SO2-SN002");  // 无 name 段

        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("应 SHOW_FORM", ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("缺省 name 应为 SO2 默认名", "Saimosen SO2 分析仪", ctx.getEntryData("name"));
    }

    @Test
    public void testImportFlow_BadVersion_Aborts() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-saimosen", 2, "air.monitor.so2|SMS8200|SN");

        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("不支持的 version 应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    @Test
    public void testImportFlow_BadFormat_Aborts() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-saimosen", 1, "only-one-field");

        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("格式错误（<3 段）应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    @Test
    public void testImportFlow_UnknownModel_Aborts() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-saimosen", 1,
                "air.monitor.so2|NOT_A_REAL_MODEL|SN");

        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("型号不属于该类型应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }

    @Test
    public void testImportFlow_UnknownClass_Aborts() {
        ImportFlowPayload payload = new ImportFlowPayload("com.ecat:integration-saimosen", 1,
                "air.monitor.unknown|X|SN");

        ConfigFlowResult result = flow.executeDiscoveryStep(SourceType.IMPORT_FLOW, payload);

        assertEquals("未知设备类型应 ABORT", ConfigFlowResult.ResultType.ABORT, result.getType());
    }
}
