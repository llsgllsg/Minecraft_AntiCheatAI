package com.gfish.anticheat;

import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 处罚管理器（对应 Grim 的 PunishmentManager）。
 * <p>
 * 集中处理"违规 -> 累进处罚 -> 封禁码 -> 违规记录落盘"的完整流程，
 * 让各检查只负责判定，不关心怎么处罚。
 */
public final class PunishmentManager {

    private static final long VIOLATION_WINDOW_MS = 5 * 60 * 1000;
    private static final int MAX_KICKS = 3;

    private final AntiCheatPlugin plugin;

    public PunishmentManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 处理一次违规：记录违规时间、生成封禁码、按 5 分钟内违规次数决定踢出或封禁。
     */
    public void handleViolation(Player player, TrackedPlayer tracked, String reason) {
        long now = System.currentTimeMillis();
        tracked.getCheatTimestamps().add(now);

        String code = UUID.randomUUID().toString().substring(0, 8);
        saveViolationRecord(player, tracked, code);

        int recent = countRecentViolations(tracked, now);
        String fullReason = reason + " [封禁码: " + code + "]";

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

    /** 把违规玩家的近期行为写入 violations/&lt;封禁码&gt;.jsonl，供 /ac lookup 查询。 */
    private void saveViolationRecord(Player player, TrackedPlayer tracked, String code) {
        BehaviorRecorder.BehaviorTick[] ticks = tracked.getRecorder().getRecentTicks(plugin.getAnalysisSeconds() * 20);
        if (ticks.length == 0) return;

        File file = new File(plugin.getViolationsFolder(), code + ".jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (BehaviorRecorder.BehaviorTick tick : ticks) {
                writer.write(toJsonLine(tick));
                writer.newLine();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存违规记录文件: " + e.getMessage());
        }
    }

    private String toJsonLine(BehaviorRecorder.BehaviorTick t) {
        return String.format(
                "{\"ts\":%d,\"pitch\":%.2f,\"yaw\":%.2f," +
                        "\"posX\":%.2f,\"posY\":%.2f,\"posZ\":%.2f," +
                        "\"placing\":%b,\"sprinting\":%b,\"jumping\":%b," +
                        "\"onGround\":%b,\"moveSpeed\":%.3f,\"vertSpeed\":%.3f}",
                t.timestamp, t.pitch, t.yaw,
                t.posX, t.posY, t.posZ,
                t.placing, t.sprinting, t.jumping,
                t.onGround, t.moveSpeed, t.vertSpeed);
    }

    private void executeKick(Player player, String reason, String code) {
        String cmd = plugin.getPunishCommand().replace("%player%", player.getName()).replace("%code%", code);
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
        plugin.getLogger().info("踢出 " + player.getName() + " 封禁码: " + code + " 原因: " + reason);
    }

    private void executeBan(Player player, String reason, String code) {
        String cmd = plugin.getBanCommand().replace("%player%", player.getName()).replace("%code%", code);
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
        plugin.getLogger().info("封禁 " + player.getName() + " 封禁码: " + code + " 原因: " + reason);
    }
}
