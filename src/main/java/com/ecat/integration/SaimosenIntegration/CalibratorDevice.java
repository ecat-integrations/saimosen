package com.ecat.integration.SaimosenIntegration;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.TextAttribute;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.integration.ModbusIntegration.ModbusSource;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusPolling;
import com.ecat.integration.ModbusIntegration.Tools;
import com.ecat.integration.ModbusIntegration.Attribute.ModbusFloatAttribute;
import com.ecat.integration.ModbusIntegration.EndianConverter.AbstractEndianConverter;
import com.ecat.integration.ModbusIntegration.EndianConverter.BigEndianConverter;

/**
 * 适用SMS校准仪-RTU
 * 
 * @version 7.106
 * @author coffee
 */
public class CalibratorDevice extends SmsDeviceBase {
    // 连续读取地址段定义
    private static final int FIRST_BLOCK_START = 0x00; // 第一块起始地址（0x00-0x07）
    private static final int FIRST_BLOCK_COUNT = 38; // 第一块读取8个寄存器（19个float*2）
    private static final int SECOND_BLOCK_START = 0x46; // 第二块起始地址（0x46-0x4A）
    private static final int SECOND_BLOCK_COUNT = 5; // 第二块读取5个寄存器（覆盖所有参数）
    private static final int GAS_SELECT_START = 0x46; // 选择气体参数

    private static final String GPTNO = "gptno_concentration"; // GPTNO气体
    private static final String GPTO3 = "gpto3_concentration"; // GPTO3气体

    BigEndianConverter bigConverter = AbstractEndianConverter.getBigEndianConverter();


    public CalibratorDevice(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        // 5 秒周期轮询：调度注册/源锁/锁忙跳过/异常韧性/统一日志全部由 ModbusPolling SDK 托管
        ModbusPolling.on(this, modbusSource)
                .round(this::readRegisters)
                .every(5, TimeUnit.SECONDS)
                .start();
    }

    @Override
    public void stop() {
        // 轮询生命周期已由 ModbusPolling SDK 内绑 RemovalHost（设备移除 sweep）收尾
    }

    @Override
    public void release() {
        super.release();
    }

    private void createAttributes() {
        // 数值型属性（大端模式float）
        setAttribute(new ModbusFloatAttribute(
                "other_gas_concentration",
                AttributeClass.OTHER_GAS_CONCENTRATION,
                AirVolumeUnit.PPM, // 原始单位
                AirVolumeUnit.PPM, // 显示单位
                3, // 显示小数位
                true, // 单位可修改
                true, // 值可修改
                modbusSource,
                (short) 0x00, // 寄存器地址
                bigConverter // 大端模式转换
        ));

        setAttribute(new ModbusFloatAttribute(
                "so2_std_gas_concentration",
                AttributeClass.SO2_STD_GAS_CONCENTRATION,
                AirVolumeUnit.PPM,
                AirVolumeUnit.PPM,
                1,
                true,
                true,
                modbusSource,
                (short) 0x02,
                bigConverter));

        setAttribute(new ModbusFloatAttribute(
                "no_std_gas_concentration",
                AttributeClass.NO_STD_GAS_CONCENTRATION,
                AirVolumeUnit.PPM,
                AirVolumeUnit.PPM,
                1,
                true,
                true,
                modbusSource,
                (short) 0x04,
                bigConverter));

        setAttribute(new ModbusFloatAttribute(
                "co_std_gas_concentration",
                AttributeClass.CO_STD_GAS_CONCENTRATION,
                AirVolumeUnit.PPM,
                AirVolumeUnit.PPM,
                1,
                true,
                true,
                modbusSource,
                (short) 0x06,
                bigConverter));

        // MFC1校准目标流量1  2026-04-11 因出现恒值 暂时停掉
//        setAttribute(new ModbusFloatAttribute("mfc1_gflow1_target", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x14, bigConverter));
//        // MFC1校准目标流量2
//        setAttribute(new ModbusFloatAttribute("mfc1_gflow2_target", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x16, bigConverter));
//        // MFC1校准实际流量1
//        setAttribute(new ModbusFloatAttribute("mfc1_gflow1_actual", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x18, bigConverter));
//        // MFC1校准实际流量2
//        setAttribute(new ModbusFloatAttribute("mfc1_gflow2_actual", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x20, bigConverter));
//        // MFC2校准目标流量1
//        setAttribute(new ModbusFloatAttribute("mfc2_gflow1_target", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x22, bigConverter));
//        // MFC2校准目标流量2
//        setAttribute(new ModbusFloatAttribute("mfc2_gflow2_target", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x24, bigConverter));
//        // MFC2校准实际流量1
//        setAttribute(new ModbusFloatAttribute("mfc2_gflow1_actual", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x26, bigConverter));
//        // MFC2校准实际流量2
//        setAttribute(new ModbusFloatAttribute("mfc2_gflow2_actual", AttributeClass.FLOW,
//                LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE,
//                1, true, false, modbusSource, (short) 0x28, bigConverter));

        // 生成样气种类（写属性，short类型）
        setAttribute(new CalibratorGasSelectAttribute(
                "calibrator_gas_select",
                AttributeClass.CALIBRATOR_GAS_SELECT,
                true,
                Arrays.asList(
                        AttributeClass.Choose.getName(),
                        AttributeClass.SO2.getName(),
                        AttributeClass.NO.getName(),
                        AttributeClass.CO.getName(),
                        AttributeClass.O3.getName(),
                        CalibratorGasSelectAttribute.M_GPTNO,
                        CalibratorGasSelectAttribute.M_GPTNO_O3,
                        CalibratorGasSelectAttribute.M_GPT
                        ),
                modbusSource,
                (short) GAS_SELECT_START)

        );

        // 系统状态（读属性，short转文本）
        setAttribute(new TextAttribute(
                "system_state",
                AttributeClass.SYSTEM_STATE,
                null,
                null,
                false // 只读
        ));

        // O3气体浓度（ppb→ppm转换）
        setAttribute(new ModbusFloatAttribute(
                "o3_gas_concentration",
                AttributeClass.O3_GAS_CONCENTRATION,
                AirVolumeUnit.PPB, // 原始单位
                AirVolumeUnit.PPM, // 显示单位
                3, // 显示小数位
                true,
                true,
                modbusSource,
                (short) 0x49,
                bigConverter));

        // GPTNO气体浓度（ppm）
        setAttribute(new ModbusFloatAttribute(
                "gptno_concentration",
                AttributeClass.NO_STD_GAS_CONCENTRATION,
                AirVolumeUnit.PPM, // 原始单位
                AirVolumeUnit.PPM, // 显示单位
                3, // 显示小数位
                true,
                true,
                modbusSource,
                (short) 0x1E,
                bigConverter));

        // GPTO3气体浓度（ppb）
        setAttribute(new ModbusFloatAttribute(
                "gpto3_concentration",
                AttributeClass.O3_GAS_CONCENTRATION,
                AirVolumeUnit.PPB, // 原始单位
                AirVolumeUnit.PPM, // 显示单位
                3, // 显示小数位
                true,
                true,
                modbusSource,
                (short) 0x20,
                bigConverter));
    }

    protected CompletableFuture<Boolean> readRegisters(ModbusSource source) {
        // 先读取第一个地址块
        return source.readHoldingRegisters(FIRST_BLOCK_START, FIRST_BLOCK_COUNT)
                .thenCompose(firstResponse -> {
                    try {
                        // 处理第一块数据
                        AttributeStatus status = AttributeStatus.NORMAL;
                        short[] firstBlockRegisters = firstResponse.getShortData();
                        parseFirstBlock(firstBlockRegisters, status);

                        // 再读取第二个地址块
                        return source.readHoldingRegisters(SECOND_BLOCK_START, SECOND_BLOCK_COUNT)
                                .thenApply(secondResponse -> {
                                    try {
                                        // 处理第二块数据
                                        short[] secondBlockRegisters = secondResponse.getShortData();
                                        parseSecondBlock(secondBlockRegisters, status);

                                        // 设置所有属性状态
                                        getAttrs().values().forEach(attr -> attr.setStatus(status));
                                        publicAttrsState();
                                        return true;
                                    } catch (Exception e) {
                                        log.error(
                                                "CalibratorDevice second block parsing failed: " + e.getMessage());
                                        getAttrs().values()
                                                .forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                                        publicAttrsState();
                                        return false;
                                    }
                                });
                    } catch (Exception e) {
                        log.error("CalibratorDevice first block parsing failed: " + e.getMessage());
                        getAttrs().values().forEach(attr -> attr.setStatus(AttributeStatus.MALFUNCTION));
                        publicAttrsState();
                        return CompletableFuture.completedFuture(false);
                    }
                });
    }

    /**
     * 解析第一块寄存器数据（0x00-0x07）
     * 
     * @param registers 寄存器值数组（长度8）
     * @param status    属性状态
     */
    private void parseFirstBlock(short[] registers, AttributeStatus status) {
        // 其他气体浓度（0x00-0x01）：大端模式（高寄存器在前）
        float otherGas = Tools.convertBigEndianToFloat(registers[0], registers[1]);
        updateModbusFloatAttribute("other_gas_concentration", otherGas, status);

        // SO2标气浓度（0x02-0x03）
        float so2 = Tools.convertBigEndianToFloat(registers[2], registers[3]);
        updateModbusFloatAttribute("so2_std_gas_concentration", so2, status);

        // NO标气浓度（0x04-0x05）
        float no = Tools.convertBigEndianToFloat(registers[4], registers[5]);
        updateModbusFloatAttribute("no_std_gas_concentration", no, status);

        // CO标气浓度（0x06-0x07）
        float co = Tools.convertBigEndianToFloat(registers[6], registers[7]);
        updateModbusFloatAttribute("co_std_gas_concentration", co, status);

//        float mfc1_gflow1_target = Tools.convertBigEndianToFloat(registers[14], registers[15]);
//        updateModbusFloatAttribute("mfc1_gflow1_target", mfc1_gflow1_target, status);
//        float mfc1_gflow2_target = Tools.convertBigEndianToFloat(registers[16], registers[17]);
//        updateModbusFloatAttribute("mfc1_gflow2_target", mfc1_gflow2_target, status);
//        float mfc1_gflow1_actual = Tools.convertBigEndianToFloat(registers[18], registers[19]);
//        updateModbusFloatAttribute("mfc1_gflow1_actual", mfc1_gflow1_actual, status);
//        float mfc1_gflow2_actual = Tools.convertBigEndianToFloat(registers[20], registers[21]);
//        updateModbusFloatAttribute("mfc1_gflow2_actual", mfc1_gflow2_actual, status);
//        float mfc2_gflow1_target = Tools.convertBigEndianToFloat(registers[22], registers[23]);
//        updateModbusFloatAttribute("mfc2_gflow1_target", mfc2_gflow1_target, status);
//        float mfc2_gflow2_target = Tools.convertBigEndianToFloat(registers[24], registers[25]);
//        updateModbusFloatAttribute("mfc2_gflow2_target", mfc2_gflow2_target, status);
//        float mfc2_gflow1_actual = Tools.convertBigEndianToFloat(registers[26], registers[27]);
//        updateModbusFloatAttribute("mfc2_gflow1_actual", mfc2_gflow1_actual, status);
//        float mfc2_gflow2_actual = Tools.convertBigEndianToFloat(registers[28], registers[29]);
//        updateModbusFloatAttribute("mfc2_gflow2_actual", mfc2_gflow2_actual, status);

        float gptno = Tools.convertBigEndianToFloat(registers[30], registers[31]);
        updateModbusFloatAttribute("gptno_concentration", gptno, status);

        float gptnoo3 = Tools.convertBigEndianToFloat(registers[32], registers[33]);
        updateModbusFloatAttribute("gpto3_concentration", gptnoo3, status);
    }

    /**
     * 解析第二块寄存器数据（0x46-0x4A）
     * 
     * @param registers 寄存器值数组（长度5）
     * @param status    属性状态
     */
    private void parseSecondBlock(short[] registers, AttributeStatus status) {
        // 生成样气种类（0x46，索引0）,不准确
        // short gasType = registers[0];
        // CalibratorGasSelectAttribute attr = (CalibratorGasSelectAttribute)
        // getAttrs().get(AttributeName.CALIBRATOR_GAS_SELECT.getName());
        // attr.updateValue(gasType);

        // 系统状态（0x48，索引2）
        short systemStateCode = registers[2];
        String systemState = parseSystemState(systemStateCode);
        updateTextAttribute("system_state", systemState);

        // O3气体浓度（0x49-0x4A，索引3-4）：大端模式转换+单位转换
        float o3Ppb = Tools.convertBigEndianToFloat(registers[3], registers[4]);
        updateModbusFloatAttribute("o3_gas_concentration", o3Ppb, status);
    }

    /**
     * 更新数值型属性值
     */
    private void updateModbusFloatAttribute(AttributeClass attrClass, float value, AttributeStatus status) {
        ModbusFloatAttribute attr = (ModbusFloatAttribute) getAttrs().get(attrClass.getName());
        attr.updateValue(value, status);
    }

    private void updateModbusFloatAttribute(String attrId, float value, AttributeStatus status) {
        ModbusFloatAttribute attr = (ModbusFloatAttribute) getAttrs().get(attrId);
        attr.updateValue(value, status);
    }

    /**
     * 更新文本型属性值（字符串）
     */
    private void updateTextAttribute(AttributeClass attrClass, String value) {
        TextAttribute attr = (TextAttribute) getAttrs().get(attrClass.getName());
        attr.updateValue(value);
    }

    private void updateTextAttribute(String attrId, String value) {
        TextAttribute attr = (TextAttribute) getAttrs().get(attrId);
        attr.updateValue(value);
    }

    /**
     * 系统状态码解析（需根据设备协议具体实现）
     */
    private String parseSystemState(short stateCode) {
        switch (stateCode) {
            case 0x01:
                return "待机";
            case 0x02:
                return "标气配制中";
            case 0x0C:
                return "O₃配置中";
            default:
                return "错误(" + stateCode + ")";
        }
    }

}
