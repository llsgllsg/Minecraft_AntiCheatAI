package com.gfish.anticheat.check;

import com.gfish.anticheat.AntiCheatPlugin;
import com.gfish.anticheat.ExemptionType;
import com.gfish.anticheat.TrackedPlayer;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 水平速度检测（原 checkPlayerSpeed，拆为独立检查）。
 * <p>
 * 内部降频到 1 秒采样一次；传送 / 击退宽限期内重置基线，避免误判。
 * 可选挂接 PlaceholderAPI 动态计算属性加成后的允许速度。
 */
public final class SpeedCheck implements Check {

    private static final long SAMPLE_INTERVAL_MS = 1000;

    private final AntiCheatPlugin plugin;
    private long lastSampleTime;

    public SpeedCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "speed";
    }

    @Override
    public void tick(Player player, TrackedPlayer tracked) {
        if (!plugin.isSpeedCheckEnabled()) return;

        long now = System.currentTimeMillis();
        if (now - lastSampleTime < SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSampleTime = now;

        if (tracked.isExemptAny(ExemptionType.TELEPORT, ExemptionType.VELOCITY)) {
            tracked.setLastSpeedLocation(null);
            return;
        }

        Location current = player.getLocation().clone();
        Location last = tracked.getLastSpeedLocation();
        if (last == null || !current.getWorld().equals(last.getWorld())) {
            tracked.setLastSpeedLocation(current);
            return;
        }

        double dx = current.getX() - last.getX();
        double dz = current.getZ() - last.getZ();
        double speed = Math.sqrt(dx * dx + dz * dz); // 1 秒采样间隔，即格/秒

        double allowed = getMaxAllowedSpeed(player);
        if (speed > allowed) {
            String cmd = plugin.getSpeedPunishCommand().replace("%player%", player.getName());
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
            plugin.getLogger().info(String.format("玩家 %s 速度过快 (%.2f > %.2f)，已执行速度处罚",
                    player.getName(), speed, allowed));
        }

        tracked.setLastSpeedLocation(current);
    }

    private double getMaxAllowedSpeed(Player player) {
        double apBonus = 0.0;
        if (plugin.isPapiEnabled()) {
            String result = PlaceholderAPI.setPlaceholders(player, plugin.getApPlaceholder());
            try {
                apBonus = Double.parseDouble(result);
            } catch (NumberFormatException ignored) {
            }
        }
        return plugin.getMaxSpeed() * (1.0 + apBonus / 100.0);
    }
}
