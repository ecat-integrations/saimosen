package com.ecat.integration.SaimosenIntegration;


import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.State.AttributeClass;
import com.ecat.integration.ModbusIntegration.Sdk.ModbusSdkTimers;

/**
 * 颗粒物零点检查8220设备，modbus通讯
 * 
 * 每次启动时会发送关闭命令停止质控状态, 解决断电重启后恢复状态
 * 
 * @version V1.0
 * @author coffee
 */
public class ParticulateZeroChecker extends SmsDeviceBase {

    /**
     * 启动自动关阀延迟（pm2.5 通道，毫秒）。包可见 volatile 测试缝：单测注入短延迟，
     * 验证「设备 stop + 框架 sweep 后待发关阀拍被撤销」的真因果（never 写阀断言），
     * 不必等生产 8s/10s。两通道错峰（8s 先 pm2.5、10s 后 pm10）保持不变。
     */
    static volatile long pm25AutoCloseDelayMs = 8_000;

    /** 启动自动关阀延迟（pm10 通道，毫秒），晚于 pm2.5 通道错峰。测试缝说明见 {@link #pm25AutoCloseDelayMs}。 */
    static volatile long pm10AutoCloseDelayMs = 10_000;

    public ParticulateZeroChecker(ConfigEntry entry) {
        super(entry);
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        // 自动关闭质控状态。
        // 启动期写副作用走 modbus 域 SDK delay 可撤销变体（统一词汇）：单发拍入域定时器，
        // 设备（RemovalHost）移除 sweep 撤销待发关阀拍——设备停止后不再写阀
        ModbusSdkTimers.delay(this, pm25AutoCloseDelayMs).thenRun(() -> {
            try {
                ParticulateZeroCheckerCommandAttribute commandPM2_5Attr = (ParticulateZeroCheckerCommandAttribute) getAttrs().get("pm2_5_zero_check_command");
                commandPM2_5Attr.sendCommand("关闭"); // 发送关闭命令
            } catch (Exception e) {
                log.error("Failed to send close command: " + e.getMessage());
            }
        });

        ModbusSdkTimers.delay(this, pm10AutoCloseDelayMs).thenRun(() -> {
            try {
                ParticulateZeroCheckerCommandAttribute commandPM10Attr = (ParticulateZeroCheckerCommandAttribute) getAttrs().get("pm10_zero_check_command");
                commandPM10Attr.sendCommand("关闭"); // 发送关闭命令
            } catch (Exception e) {
                log.error("Failed to send close command: " + e.getMessage());
            }
        });
    }

    @Override
    public void stop() {
        // 无周期轮询任务（命令型设备）；两个自动关阀延迟拍经 start 期的 RemovalHost
        // 移除动作随框架 cancelManagedTasks() sweep 撤销
    }

    @Override
    public void release() {
        super.release();
    }

    /**
     * 创建设备属性
     */
    private void createAttributes() {
        setAttribute(new ParticulateZeroCheckerCommandAttribute(
                "pm10_zero_check_command",
                AttributeClass.DISPATCH_COMMAND,
                modbusSource, (short)0x01, (short)0x02));

        setAttribute(new ParticulateZeroCheckerCommandAttribute(
                "pm2_5_zero_check_command",
                AttributeClass.DISPATCH_COMMAND,
                modbusSource, (short)0x03, (short)0x04));
    }

}
