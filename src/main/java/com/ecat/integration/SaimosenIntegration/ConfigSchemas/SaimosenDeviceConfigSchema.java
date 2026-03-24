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

package com.ecat.integration.SaimosenIntegration.ConfigSchemas;

import com.ecat.core.ConfigFlow.ConfigItem.EnumConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.FloatConfigItem;
import com.ecat.core.ConfigFlow.ConfigItem.TextConfigItem;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigSchemaProvider;

/**
 * Saimosen 设备配置 Schema
 * <p>
 * 定义 Saimosen 设备的基本配置字段：
 * <ul>
 *   <li>class - 设备类型（必填，使用 DeviceClasses 枚举值）</li>
 *   <li>name - 设备名称（必填）</li>
 *   <li>sn - 序列号（可选）</li>
 *   <li>vendor - 厂商（自动填充为 "saimosen"）</li>
 *   <li>sampling_tube_length - 采样管长度（可选，QCDevice 专用）</li>
 * </ul>
 *
 * <p>注意：comm_settings 不在此 Schema 中，由 SaimosenConfigFlow 根据用户选择的
 * 协议类型（RTU/TCP）动态选择对应的通讯配置 Schema。
 *
 * @author coffee
 */
public class SaimosenDeviceConfigSchema implements ConfigSchemaProvider {

    @Override
    public ConfigSchema createSchema() {
        return new ConfigSchema()
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
            .addField(new TextConfigItem("name", true).displayName("设备名称").length(1, 50))
            .addField(new TextConfigItem("sn", false).displayName("序列号"))
            .addField(new TextConfigItem("vendor", false).displayName("厂商"))
            .addField(new FloatConfigItem("sampling_tube_length", false)
                .displayName("采样管长度(m)")
                .range(0, 100));
    }
}
