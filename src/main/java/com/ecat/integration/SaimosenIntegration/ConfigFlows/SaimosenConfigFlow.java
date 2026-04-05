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

import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.BooleanConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.TextConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.YamlConfigItem;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.ConfigEntry.ConfigEntryRegistry;
import com.ecat.core.ConfigEntry.SourceType;
import com.ecat.core.Utils.DateTimeUtils;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusCommTypeSchema;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusRtuCommConfigSchema;
import com.ecat.integration.ModbusIntegration.ConfigSchemas.ModbusTcpCommConfigSchema;
import com.ecat.integration.SaimosenIntegration.SaimosenIntegration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Saimosen 设备配置流程
 * <p>
 * 流程步骤：user -> device_config -> [qc_config] -> protocol_select -> comm_config -> final_confirm
 * <ul>
 *   <li>device_config: 设备基本配置（类型、名称、序列号）</li>
 *   <li>qc_config: 质控仪专用配置（采样管长度），仅选择质控仪时出现</li>
 *   <li>protocol_select: 选择 Modbus 协议类型（RTU/TCP）</li>
 *   <li>comm_config: 根据协议类型动态显示 RTU 或 TCP 通讯配置</li>
 *   <li>final_confirm: 确认并创建配置条目</li>
 * </ul>
 *
 * @author coffee
 */
public class SaimosenConfigFlow extends AbstractConfigFlow {

    /** 厂商名称 */
    private static final String VENDOR = "saimosen";

    /** 流程类型标识 */
    private static final String FLOW_TYPE = "saimosen-device-config";

    /** 质控仪设备类型标识 */
    private static final String QC_CLASS = "air.monitor.qc";

    public SaimosenConfigFlow() {
        super();

        registerStepUser("user", "配置 Saimosen 设备", this::stepUser);
        registerStepReconfigure("reconfigure", "重新配置 Saimosen 设备", this::stepReconfigure);

        registerStep("device_config", this::stepDeviceConfig, "设备配置");
        registerStep("qc_config", this::stepQcConfig, "质控仪配置");
        registerStep("protocol_select", this::stepProtocolSelect, "协议选择");
        registerStep("comm_config", this::stepCommConfig, "通讯配置");
        registerStep("final_confirm", this::stepFinalConfirm, "确认配置");
    }

    // ========== 入口步骤处理器 ==========

    private ConfigFlowResult stepUser(Map<String, Object> userInput, FlowContext context) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("user", createWelcomeSchema(), new HashMap<>());
        }
        return showForm("device_config", createDeviceBasicSchema(), new HashMap<>());
    }

    private ConfigFlowResult stepReconfigure(Map<String, Object> userInput, FlowContext context) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("reconfigure", createReconfigureSchema(), new HashMap<>());
        }
        return showForm("device_config", createDeviceBasicSchema(), new HashMap<>());
    }

    // ========== 步骤处理器 ==========

    private ConfigFlowResult stepDeviceConfig(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("device_config", createDeviceBasicSchema(), new HashMap<>());
        }
        ConfigSchema schema = createDeviceBasicSchema();
        Map<String, Object> errors = schema.validate(userInput);
        if (!errors.isEmpty()) {
            return showForm("device_config", schema, errors);
        }
        context.getEntryData().putAll(userInput);
        context.getEntryData().put("vendor", VENDOR);
        // 根据设备类型自动设置硬件型号
        String deviceClass = (String) userInput.get("class");
        String model = SaimosenIntegration.classToModel(deviceClass);
        if (model != null) {
            context.getEntryData().put("model", model);
        }
        // 质控仪需要额外配置采样管长度
        if (QC_CLASS.equals(userInput.get("class"))) {
            return showForm("qc_config", createQcConfigSchema(), new HashMap<>());
        }
        return showForm("protocol_select", new ModbusCommTypeSchema().createSchema(), new HashMap<>());
    }

    /**
     * 质控仪专用配置步骤 - 采样管长度
     */
    private ConfigFlowResult stepQcConfig(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("qc_config", createQcConfigSchema(), new HashMap<>());
        }
        ConfigSchema schema = createQcConfigSchema();
        Map<String, Object> errors = schema.validate(userInput);
        if (!errors.isEmpty()) {
            return showForm("qc_config", schema, errors);
        }
        // 将采样管长度嵌套到 device_settings map 中，避免污染 entryData 顶层
        Map<String, Object> deviceSettings = new HashMap<>();
        Object tubeLengthObj = userInput.get("sampling_tube_length");
        if (tubeLengthObj != null) {
            deviceSettings.put("sampling_tube_length", Double.parseDouble(tubeLengthObj.toString()));
        }
        context.getEntryData().put("device_settings", deviceSettings);
        return showForm("protocol_select", new ModbusCommTypeSchema().createSchema(), new HashMap<>());
    }

    private ConfigFlowResult stepProtocolSelect(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("protocol_select", new ModbusCommTypeSchema().createSchema(), new HashMap<>());
        }
        ConfigSchema schema = new ModbusCommTypeSchema().createSchema();
        Map<String, Object> errors = schema.validate(userInput);
        if (!errors.isEmpty()) {
            return showForm("protocol_select", schema, errors);
        }
        // 保存选择的协议类型
        context.getEntryData().putAll(userInput);
        // 根据协议类型选择通讯配置 Schema
        String protocol = (String) userInput.get("modbus_protocol");
        return showForm("comm_config", createCommConfigSchema(protocol), new HashMap<>());
    }

    private ConfigFlowResult stepCommConfig(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            // 根据已选择的协议类型显示对应 Schema
            String protocol = (String) context.getEntryData().getOrDefault("modbus_protocol", "RTU");
            return showForm("comm_config", createCommConfigSchema(protocol), new HashMap<>());
        }
        String protocol = (String) context.getEntryData().getOrDefault("modbus_protocol", "RTU");
        ConfigSchema schema = createCommConfigSchema(protocol);
        Map<String, Object> errors = schema.validate(userInput);
        if (!errors.isEmpty()) {
            return showForm("comm_config", schema, errors);
        }
        context.getEntryData().put("comm_settings", userInput);
        return showForm("final_confirm", createFinalConfirmSchema(), new HashMap<>());
    }

    private ConfigFlowResult stepFinalConfirm(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("final_confirm", createFinalConfirmSchema(), new HashMap<>());
        }
        Boolean confirmed = userInput.get("confirmed") != null
            ? Boolean.valueOf(userInput.get("confirmed").toString()) : false;
        if (!confirmed) {
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("confirmed", "请确认配置信息");
            return showForm("final_confirm", createFinalConfirmSchema(), errorMap);
        }

        context.getEntryData().put("flow_type", FLOW_TYPE);
        context.getEntryData().put("created_at", DateTimeUtils.now());

        String uniqueId = generateUniqueId();

        boolean isReconfigure = getSourceType() == SourceType.RECONFIGURE;
        try {
            context.setEntryUniqueId(uniqueId, isReconfigure);
        } catch (ConfigEntryRegistry.DuplicateUniqueIdException e) {
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("confirmed", "设备名称已存在，请修改设备名称");
            return showForm("final_confirm", createFinalConfirmSchema(), errorMap);
        }

        String title = (String) context.getEntryData().getOrDefault("name", "Saimosen设备");
        context.setEntryTitle(title);

        return createEntry();
    }

    // ========== Schema 创建方法 ==========

    private ConfigSchema createWelcomeSchema() {
        return new ConfigSchema()
            .addField(new TextConfigItem("welcome", false,
                "欢迎使用 Saimosen 设备配置向导！\n\n本向导将帮助您配置 Saimosen 系列环境监测设备。\n支持 Modbus RTU (RS485) 和 Modbus TCP 协议。")
                .displayName("欢迎"));
    }

    private ConfigSchema createReconfigureSchema() {
        return new ConfigSchema()
            .addField(new TextConfigItem("reconfigure_info", false,
                "您正在重新配置 Saimosen 设备。\n\n请修改需要更改的配置项。")
                .displayName("重新配置"));
    }

    /**
     * 创建设备基本配置 Schema
     * <p>
     * 包含设备类型（class）、名称、序列号。
     * 采样管长度在选择质控仪后由 qc_config 步骤收集。
     */
    private ConfigSchema createDeviceBasicSchema() {
        return new ConfigSchema()
            .addField(new TextConfigItem("class_type_label", false,
                "请选择设备类型。支持校准器、质控仪、分析仪等多种 Saimosen 环境监测设备。")
                .displayName("设备类型说明"))
            .addField(new EnumConfigItem("class", true, "air.monitor.calibrator")
                .displayName("设备类型")
                .addOption("air.monitor.calibrator", "校准器")
                .addOption("air.monitor.qc", "质控仪")
                .addOption("power.supply.stabilizer", "智能稳压电源")
                .addOption("sample.tube", "采样管")
                .addOption("air.monitor.pm.qc", "颗粒物零点校验仪")
                .addOption("air.monitor.o3", "O3 分析仪")
                .addOption("air.monitor.no2", "NO2 分析仪")
                .addOption("air.monitor.co", "CO 分析仪")
                .addOption("air.monitor.so2", "SO2 分析仪")
                .buildValidator())
            .addField(new TextConfigItem("name", true)
                .displayName("设备名称")
                .length(1, 50)
                .setDefaultValue("Saimosen设备"))
            .addField(new TextConfigItem("sn", false)
                .displayName("序列号"));
    }

    /**
     * 创建质控仪专用配置 Schema（采样管长度）
     */
    private ConfigSchema createQcConfigSchema() {
        return new ConfigSchema()
            .addField(new TextConfigItem("qc_config_label", false,
                "质控仪需要配置采样管长度，请填写实际使用的采样管长度。")
                .displayName("质控仪配置说明"))
            .addField(new TextConfigItem("sampling_tube_length", false)
                .displayName("采样管长度(m)"));
    }

    /**
     * 根据协议类型创建通讯配置 Schema
     *
     * @param protocol 协议类型 ("RTU" 或 "TCP")
     * @return 对应的通讯配置 Schema
     */
    private ConfigSchema createCommConfigSchema(String protocol) {
        if ("TCP".equals(protocol)) {
            return new ModbusTcpCommConfigSchema().createSchema();
        } else {
            // 默认 RTU
            return new ModbusRtuCommConfigSchema().createSchema();
        }
    }

    private ConfigSchema createFinalConfirmSchema() {
        // 构建显示数据：排除框架内部字段
        Map<String, Object> displayData = new LinkedHashMap<>();
        displayData.putAll(context.getEntryData());
        displayData.remove("flow_type");
        displayData.remove("created_at");
        // 移除说明性字段
        displayData.remove("class_type_label");
        displayData.remove("qc_config_label");

        return new ConfigSchema()
            .addField(new YamlConfigItem("config_summary")
                .displayName("配置详情")
                .setValue(displayData))
            .addField(new BooleanConfigItem("confirmed", true, false)
                .displayName("确认创建配置"));
    }

    // ========== 辅助方法 ==========

    /**
     * 生成唯一标识符
     * <p>
     * 格式：saimosen_{class}[_SN{sn}]
     * 例如：saimosen_air.monitor.co_SN001
     */
    private String generateUniqueId() {
        String deviceClass = (String) context.getEntryData().getOrDefault("class", "");
        String sn = (String) context.getEntryData().getOrDefault("sn", "");
        if (sn == null || sn.isEmpty()) {
            return "saimosen_" + deviceClass;
        }
        return "saimosen_" + deviceClass + "_SN" + sn;
    }
}
