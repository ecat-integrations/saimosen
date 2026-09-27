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
 * SMS8500V2 一氧化碳分析仪。串口协议对齐先河 XHCO2000BV2（{@code coochr$} / {@code cootwc$}）。
 */
public class SMS8500V2Device extends SmsV2GasDeviceBase {

    public Double molecularWeight = 28.0;

    private static final String REAL_DATA_CMD = "coochr$";
    private static final String STATUS_CMD = "cootwc$";
    private ByteResponseHandlerStrategy<byte[]> responseHandlerStrategy;

    public SMS8500V2Device(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public String getTypeName() {
        return "sms8500v2device";
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
        setAttribute(new AQAttribute("CO", AttributeClass.CO, AirVolumeUnit.PPM, AirVolumeUnit.PPM, 2, true, false, molecularWeight));
        setAttribute(new NumericAttribute("PMT_V", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("CANBI_V", AttributeClass.VOLTAGE, VoltageUnit.MILLIVOLT, VoltageUnit.MILLIVOLT, 3, true, false));
        setAttribute(new NumericAttribute("PRESS", AttributeClass.PRESSURE, PressureUnit.KPA, PressureUnit.KPA, 3, true, false));
        setAttribute(new NumericAttribute("FLOW", AttributeClass.FLOW, LiterFlowUnit.ML_PER_MINUTE, LiterFlowUnit.ML_PER_MINUTE, 3, true, false));
        setAttribute(new NumericAttribute("TEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));
        setAttribute(new NumericAttribute("XG_TEMP", AttributeClass.TEMPERATURE, TemperatureUnit.CELSIUS, TemperatureUnit.CELSIUS, 3, true, false));

        setAttribute(new NumericAttribute("CALIBRATION_CONCENTRATION", AttributeClass.CO, AirVolumeUnit.PPM, AirVolumeUnit.PPM, 3, true, true));

        SmsV2GasCommandAttribute commandAttr = new SmsV2GasCommandAttribute("CALIBRATION_CMD", AttributeClass.DISPATCH_COMMAND, serialSource);
        commandAttr.registerCommand("ZERO_START", new SmsV2GasCommandAttribute.CommandConfig(
                "czeros$", "czerosok$", "czerosfa$", SmsV2GasCommandAttribute.CommandType.ZERO_START));
        commandAttr.registerCommand("ZERO_END", new SmsV2GasCommandAttribute.CommandConfig(
                "czeroe$", "czeroeok$", "czeroefa$", SmsV2GasCommandAttribute.CommandType.ZERO_END));
        commandAttr.registerCommand("ZERO_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "czeroq$", "czeroqok$", "czeroqfa$", SmsV2GasCommandAttribute.CommandType.ZERO_CONFIRM));
        commandAttr.registerCommand("SPAN_START", new SmsV2GasCommandAttribute.CommandConfig(
                "cspans {value}$", "cspansok$", "cspansfa$", SmsV2GasCommandAttribute.CommandType.SPAN_START));
        commandAttr.registerCommand("SPAN_END", new SmsV2GasCommandAttribute.CommandConfig(
                "cspane {value}$", "cspaneok$", "cspanefa$", SmsV2GasCommandAttribute.CommandType.SPAN_END));
        commandAttr.registerCommand("SPAN_CONFIRM", new SmsV2GasCommandAttribute.CommandConfig(
                "cspanq {value}$", "cspanqok$", "cspanqfa$", SmsV2GasCommandAttribute.CommandType.SPAN_CONFIRM));
        commandAttr.addDependencyAttribute((NumericAttribute) getAttrs().get("CALIBRATION_CONCENTRATION"));
        setAttribute(commandAttr);

        addManualStatusAttributes("co");
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
                if (result.matches("^[*#]?CO=.*\\$")) {
                    updateCOAttribute(result);
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

    private void updateCOAttribute(String valueStr) {
        AQAttribute coAttr = (AQAttribute) getAttrs().get("CO");
        if (coAttr == null) {
            log.warn("CO attribute not found");
            return;
        }
        try {
            AttributeStatus status = determineDataStatus(valueStr, "co_manual_status", null);
            deviceStatus = mapAttributeStatusToDeviceStatus(status);

            String numericPart = valueStr;
            if (valueStr.startsWith("*") || valueStr.startsWith("#")) {
                numericPart = valueStr.substring(1);
            }
            numericPart = numericPart.replaceFirst("[*#]?CO=", "").replace("$", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^([-+]?\\d*\\.?\\d+)").matcher(numericPart);
            double value = 0.0;
            if (matcher.find()) {
                value = Double.parseDouble(matcher.group(1));
            } else {
                log.error("CO value regex parse error: " + valueStr);
            }
            coAttr.updateValue(value, status);
            updateReadonlyStatusAttribute("co_status", status);
            publicAttrsState();
        } catch (Exception e) {
            log.error("CO value parse error: " + valueStr);
        }
    }

    private void parseStatusResponse(String result) {
        String statusStr = result.replace("$", "");
        String[] parts = statusStr.split(",");
        if (parts.length != 10) {
            return;
        }
        AttributeStatus status = determineDataStatus("", "co_manual_status", null);
        updateAttribute("PMT_V", parts[0], status);
        updateAttribute("CANBI_V", parts[1], status);
        updateAttribute("PRESS", parts[2], status);
        updateAttribute("FLOW", parts[3], status);
        updateAttribute("TEMP", parts[4], status);
        updateAttribute("XG_TEMP", parts[5], status);
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
