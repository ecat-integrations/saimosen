package com.ecat.integration.SaimosenIntegration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AQAttribute;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeClass;
import com.ecat.core.State.AttributeStatus;
import com.ecat.core.State.NumericAttribute;
import com.ecat.core.State.Unit.AirVolumeUnit;
import com.ecat.core.State.Unit.LiterFlowUnit;
import com.ecat.core.State.Unit.PressureUnit;
import com.ecat.core.State.Unit.TemperatureUnit;
import com.ecat.core.State.Unit.VoltageUnit;
import com.ecat.integration.SerialIntegration.SerialPolling;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlerStrategy;
import com.ecat.integration.SerialIntegration.SendReadStrategy.ByteResponseHandlingContext;

/**
 * SMS8200V2 二氧化硫分析仪。串口协议对齐先河 XHS2000BV2（{@code so2chr$} / {@code so2twc$}）。
 */
public class SMS8200V2Device extends SmsV2GasDeviceBase {

    public Double molecularWeight = 64.0;

    private static final String REAL_DATA_CMD = "so2chr$";
    private static final String STATUS_CMD = "so2twc$";
    private ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy;

    public SMS8200V2Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public String getTypeName() {
        return "sms8200v2device";
    }

    @Override
    public void init() {
        super.init();
        this.responseHandlerStrategy = new ByteResponseHandlerStrategy<>(
                serialSource,
                this::processResponse,
                this::checkByteResponse,
                this::handleException
        );
        createAttributes();
    }

    @Override
    public void start() {
        SerialPolling.on(this, serialSource)
                .round(source -> getRealData()
                        .thenCompose(v -> getStatusData()))
                .every(pollIntervalMs, TimeUnit.MILLISECONDS)
                .start();
    }

    private void createAttributes() {
        setAttribute(new AQAttribute("SO2", AttributeClass.SO2, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 2, true, false, molecularWeight));
        setAttribute(new NumericAttribute("PMT_V", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("CANBI_V", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("POWER", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("PRESS", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        setAttribute(new NumericAttribute("FLOW", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        setAttribute(new NumericAttribute("TEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("BOXTEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("PMT_HVDIS", AttributeClass.VOLTAGE, VoltageUnit.VOLT, VoltageUnit.VOLT, 3, true, false));
        setAttribute(new NumericAttribute("XIANDENG_HV", AttributeClass.VOLTAGE, VoltageUnit.VOLT, VoltageUnit.VOLT, 3, true, false));

        setAttribute(new NumericAttribute("CALIBRATION_CONCENTRATION", AttributeClass.SO2, AirVolumeUnit.PPB, AirVolumeUnit.PPB, 3, true, true));

        SmsV2GasCommandAttribute commandAttr = new SmsV2GasCommandAttribute("CALIBRATION_CMD", AttributeClass.DISPATCH_COMMAND, serialSource);
        commandAttr.registerCommand("ZERO_START", new SmsV2GasCommandAttribute.CommandConfig(
                "szeros$", "szerosok$", "szerosfa$", SmsV2GasCommandAttribute.CommandType.ZERO_START));
        commandAttr.registerCommand("ZERO_END", new SmsV2GasCommandAttribute.CommandConfig(
                "szeroe$", "szeroeok$", "szeroefa$", SmsV2GasCommandAttribute.CommandType.ZERO_END));
        commandAttr.registerCommand("ZERO_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "szeroq$", "szeroqok$", "szeroqfa$", SmsV2GasCommandAttribute.CommandType.ZERO_CONFIRM));
        commandAttr.registerCommand("SPAN_START", new SmsV2GasCommandAttribute.CommandConfig(
                "sspans {value}$", "spansok$", "spansfa$", SmsV2GasCommandAttribute.CommandType.SPAN_START));
        commandAttr.registerCommand("SPAN_END", new SmsV2GasCommandAttribute.CommandConfig(
                "sspane {value}$", "spaneok$", "spanefa$", SmsV2GasCommandAttribute.CommandType.SPAN_END));
        commandAttr.registerCommand("SPAN_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "sspanq {value}$", "spanqok$", "spanqfa$", SmsV2GasCommandAttribute.CommandType.SPAN_CONFIRM));
        commandAttr.addDependencyAttribute((NumericAttribute) getAttrs().get("CALIBRATION_CONCENTRATION"));
        setAttribute(commandAttr);

        addManualStatusAttributes("so2");
    }

    private CompletableFuture<Boolean> getRealData() {
        return sendCommand(REAL_DATA_CMD.getBytes());
    }

    private CompletableFuture<Boolean> getStatusData() {
        return sendCommand(STATUS_CMD.getBytes());
    }

    private CompletableFuture<Boolean> sendCommand(byte[] cmd) {
        return serialSource.asyncSendData(cmd)
               .thenCompose(v -> responseHandlerStrategy.handleResponse(new ByteResponseHandlingContext<>(cmd)));
    }

    private Boolean processResponse(ByteResponseHandlingContext<byte[]> context) {
        String result = context.getReceiveBuffer().toString();
        if (result.endsWith("$")) {
            result = result.replace("$$", "$");
            result = result.replace("\r\n", "");
            String sentCmd = new String(context.getNewValue());
            if (sentCmd.equals(REAL_DATA_CMD)) {
                if (result.matches("^[*#]?SO2=.*\\$")) {
                    updateSO2Attribute(result);
                    return true;
                }
            } else if (sentCmd.equals(STATUS_CMD)) {
                parseStatusResponse(result);
                return true;
            }
        }
        log.warn("Unprocessed response: " + result);
        return false;
    }

    private void updateSO2Attribute(String valueStr) {
        AQAttribute so2Attr = (AQAttribute) getAttrs().get("SO2");
        if (so2Attr == null) {
            log.warn("SO2 attribute not found");
            return;
        }
        try {
            AttributeStatus status = determineDataStatus(valueStr, "so2_manual_status", null);
            deviceStatus = mapAttributeStatusToDeviceStatus(status);

            String numericPart = valueStr;
            if (valueStr.startsWith("*") || valueStr.startsWith("#")) {
                numericPart = valueStr.substring(1);
            }
            numericPart = numericPart.replaceFirst("[*#]?SO2=", "").replace("$", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^([-+]?\\d*\\.?\\d+)").matcher(numericPart);
            double value = 0.0;
            if (matcher.find()) {
                value = Double.parseDouble(matcher.group(1));
            } else {
                log.error("SO2 value regex parse error: " + valueStr);
            }
            so2Attr.updateValue(value, status);
            updateReadonlyStatusAttribute("so2_status", status);
            publicAttrsState();
        } catch (Exception e) {
            log.error("SO2 value parse error: " + valueStr);
        }
    }

    private void parseStatusResponse(String result) {
        String statusStr = result.replace("$", "");
        String[] parts = statusStr.split(",");
        if (parts.length != 9) {
            return;
        }
        AttributeStatus status = determineDataStatus("", "so2_manual_status", null);
        updateAttribute("PMT_V", parts[0], status);
        updateAttribute("CANBI_V", parts[1], status);
        updateAttribute("POWER", parts[2], status);
        updateAttribute("PRESS", parts[3], status);
        updateAttribute("FLOW", parts[4], status);
        updateAttribute("TEMP", parts[5], status);
        updateAttribute("BOXTEMP", parts[6], status);
        updateAttribute("PMT_HVDIS", parts[7], status);
        updateAttribute("XIANDENG_HV", parts[8], status);
        publicAttrsState();
    }

    private void updateAttribute(String attrId, String valueStr, AttributeStatus status) {
        AttributeBase<?> attr = getAttrs().get(attrId);
        if (attr == null) {
            log.warn("Attribute not found: " + attrId);
            return;
        }
        if (attr instanceof NumericAttribute) {
            try {
                double value = Double.parseDouble(valueStr);
                ((NumericAttribute) attr).updateValue(value, status);
            } catch (NumberFormatException e) {
                log.error("Value is not numeric for NumericAttribute: " + attrId + " = " + valueStr);
            }
        }
    }
}
