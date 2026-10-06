package com.gfish.anticheat.core;

import com.gfish.anticheat.BehaviorRecorder;
import com.gfish.anticheat.core.config.DeepGuardConfig;
import com.gfish.anticheat.core.platform.DeepGuardPlatform;
import com.gfish.anticheat.core.platform.PlayerHandle;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 处罚管理器（对应 Grim 的 PunishmentManager）。
 * <p>
 * 集中处理"违规 -> 累进处罚 -> 封禁码 -> 违规记录落盘"的完整流程，
 * 让各检查只负责判定，不关心怎么处罚。
 */
public final class PunishmentManager {

    private static final long VIOLATION_WINDOW_MS = 5 * 60 * 1000;
    private static final int MAX_KICKS = 3;

    private final DeepGuardPlatform platform;
    private final Supplier<DeepGuardConfig> config;
    private final LongSupplier clock;
    private final Path violationsFolder;

    public PunishmentManager(DeepGuardPlatform platform, Supplier<DeepGuardConfig> config) {
        this(platform, config, System::currentTimeMillis);
    }

    public PunishmentManager(DeepGuardPlatform platform, Supplier<DeepGuardConfig> config, LongSupplier clock) {
        this.platform = platform;
        this.config = config;
        this.clock = clock;
        this.violationsFolder = platform.dataFolder().resolve("violations");
        try {
            Files.createDirectories(violationsFolder);
        } catch (IOException e) {
            platform.logger().warn("无法创建违规记录目录: " + e.getMessage());
        }
    }

    /** 违规记录存放目录（{@code 数据目录/violations}）。 */
    public Path violationsFolder() {
        return violationsFolder;
    }

    /**
     * 处理一次违规：记录违规时间、生成封禁码、按 5 分钟内违规次数决定踢出或封禁。
     * <p>
     * ⚠️ 顺序不能改：<b>先把行为落盘再判定处罚</b>。反过来的话，一旦踢人命令
     * 让玩家掉线、录制器被清空，记录就写不出来了 —— 而那条记录正是事后申诉
     * 唯一的凭据。
     */
    public void handleViolation(PlayerHandle player, TrackedPlayer tracked, String reason) {
        long now = clock.getAsLong();
        tracked.getCheatTimestamps().add(now);

        String code = UUID.randomUUID().toString().substring(0, 8);
        saveViolationRecord(tracked, code);

        int recent = countRecentViolations(tracked, now);
        String fullReason = reason + " [封禁码: " + code + "]";

        // 严格大于：第 4 次才封禁
        if (recent > MAX_KICKS) {
            executeBan(player, fullReason, code);
        } else {
            executeKick(player, fullReason, code);
        }

        cleanupOldTimestamps(tracked, now);
    }

    private int countRecentViolations(TrackedPlayer tracked, long now) {
        long cutoff = now - VIOLATION_WINDOW_MS;
        return (int) tracked.getCheatTimestamps().stream().filter(t -> t >= cutoff).count();
    }

    private void cleanupOldTimestamps(TrackedPlayer tracked, long now) {
        long cutoff = now - VIOLATION_WINDOW_MS;
        tracked.getCheatTimestamps().removeIf(t -> t < cutoff);
    }

    /** 把违规玩家的近期行为写入 {@code violations/<封禁码>.jsonl}，供 {@code /ac lookup} 查询。 */
    private void saveViolationRecord(TrackedPlayer tracked, String code) {
        BehaviorRecorder.BehaviorTick[] ticks =
                tracked.getRecorder().getRecentTicks(config.get().analysisSeconds * 20);
        if (ticks.length == 0) return;

        Path file = violationsFolder.resolve(code + ".jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (BehaviorRecorder.BehaviorTick tick : ticks) {
                writer.write(toJsonLine(tick));
                writer.newLine();
            }
        } catch (IOException e) {
            platform.logger().warn("无法保存违规记录文件: " + e.getMessage());
        }
    }

    /**
     * 违规记录的一行 JSON。
     * <p>
     * ⚠️ 字段名、字段顺序、小数位数都不能动：{@code /ac lookup} 用
     * {@code indexOf("\"pitch\":")} 这种字符串定位来解析，Python 侧的数据管线
     * 也认识这个格式。
     * <p>
     * ⚠️ {@code Locale.ROOT} 是必须的：默认 Locale 在德语等区域会把小数点写成
     * 逗号（{@code 1,23}），既破坏 {@code /ac lookup} 的解析，也让训练管线读不进来。
     */
    private String toJsonLine(BehaviorRecorder.BehaviorTick t) {
        return String.format(Locale.ROOT,
                "{\"ts\":%d,\"pitch\":%.2f,\"yaw\":%.2f," +
                        "\"posX\":%.2f,\"posY\":%.2f,\"posZ\":%.2f," +
                        "\"placing\":%b,\"sprinting\":%b,\"jumping\":%b," +
                        "\"onGround\":%b,\"moveSpeed\":%.3f,\"vertSpeed\":%.3f}",
                t.timestamp, t.pitch, t.yaw,
                t.posX, t.posY, t.posZ,
                t.placing, t.sprinting, t.jumping,
                t.onGround, t.moveSpeed, t.vertSpeed);
    }

    private void executeKick(PlayerHandle player, String reason, String code) {
        String cmd = config.get().punishCommand
                .replace("%player%", player.name()).replace("%code%", code);
        platform.dispatchConsoleCommand(cmd);
        platform.logger().info("踢出 " + player.name() + " 封禁码: " + code + " 原因: " + reason);
    }

    private void executeBan(PlayerHandle player, String reason, String code) {
        String cmd = config.get().banCommand
                .replace("%player%", player.name()).replace("%code%", code);
        platform.dispatchConsoleCommand(cmd);
        platform.logger().info("封禁 " + player.name() + " 封禁码: " + code + " 原因: " + reason);
    }
}
