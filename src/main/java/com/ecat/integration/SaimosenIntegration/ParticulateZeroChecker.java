package com.ecat.integration.SaimosenIntegration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.State.AttributeClass;

/**
 * 颗粒物零点检查8220设备，modbus通讯
 * 
 * 每次启动时会发送关闭命令停止质控状态, 解决断电重启后恢复状态
 * 
 * @version V1.0
 * @author coffee
 */
public class ParticulateZeroChecker extends SmsDeviceBase {

    // 读取任务
    private ScheduledFuture<?> readFuture;

    private ScheduledFuture<?> testControlFuture;
    private boolean isDebug = false; // 是否开启调试模式



    public ParticulateZeroChecker(Map<String, Object> config) {
        super(config);
    }

    @Override
    public void init() {
        super.init();
        createAttributes();
    }

    @Override
    public void start() {
        // 定时控制任务（模拟切换模式）
        if (isDebug) {
            testControlFuture = getScheduledExecutor().scheduleWithFixedDelay(this::controlMode, 10, 60, TimeUnit.SECONDS);
        }

        // 自动关闭质控状态
        getScheduledExecutor().schedule(() -> {
            try {
                ParticulateZeroCheckerCommandAttribute commandPM2_5Attr = (ParticulateZeroCheckerCommandAttribute) getAttrs().get("pm2_5_zero_check_command");
                commandPM2_5Attr.sendCommand("关闭"); // 发送关闭命令
            } catch (Exception e) {
                log.error("Failed to send close command: " + e.getMessage());
            }
        }, 8, TimeUnit.SECONDS);

        getScheduledExecutor().schedule(() -> {
            try {
                ParticulateZeroCheckerCommandAttribute commandPM10Attr = (ParticulateZeroCheckerCommandAttribute) getAttrs().get("pm10_zero_check_command");
                commandPM10Attr.sendCommand("关闭"); // 发送关闭命令
            } catch (Exception e) {
                log.error("Failed to send close command: " + e.getMessage());
            }
        }, 10, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (readFuture != null)
            readFuture.cancel(true);
        if (testControlFuture != null) testControlFuture.cancel(true);
    }

    @Override
    public void release() {
        if (readFuture != null) {
            readFuture.cancel(true);
        }
        if (testControlFuture != null) testControlFuture.cancel(true);
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

    /**
     *  测试控制模式切换
     *  注意：使用时仅能将集成yml配置当前一台设备，否则查找设备会报错
     */
    private void controlMode() {
        ParticulateZeroCheckerCommandAttribute commandAttr = (ParticulateZeroCheckerCommandAttribute) getAttrs().get("pm2_5_zero_check_command");

        // 获取命令列表和当前最后一条命令
        List<String> commands = commandAttr.getCommands();
        String lastCommand = commandAttr.getLastCommand();
        // 查找下一条命令
        String nextCommand = getNextCommand(commands, lastCommand);

        try {
            // 发送命令
            Collection<DeviceBase> device = ((SaimosenIntegration)getIntegration()).getAllDevices();
            for (DeviceBase dev : device) {
                ((ParticulateZeroCheckerCommandAttribute)dev.getAttrs().get("pm2_5_zero_check_command")).sendCommand(nextCommand);
            }
            // commandAttr.sendCommand(nextCommand).get();
        } catch (Exception e) {
            log.error("Failed to send command: " + e.getMessage());
        }
    }
    // 辅助方法：获取下一条命令
    private String getNextCommand(List<String> commands, String lastCommand) {
        if (commands == null || commands.isEmpty()) {
            return null;
        }
        
        // 如果lastCommand为空或不在列表中，返回第一条命令
        if (lastCommand == null || !commands.contains(lastCommand)) {
            return commands.get(0);
        }
        
        // 查找当前命令的索引
        int currentIndex = commands.indexOf(lastCommand);
        
        // 计算下一条命令的索引（如果是最后一条，则返回第一条）
        int nextIndex = (currentIndex + 1) % commands.size();
        
        return commands.get(nextIndex);
    }
}
