package com.gfish.anticheat.check;

import com.gfish.anticheat.AntiCheatPlugin;
import com.gfish.anticheat.TrackedPlayer;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;

/**
 * 船速检测（原 checkBoatSpeed，拆为独立检查）。
 * <p>
 * 改进：改用 {@link MovementProcessor} 计算的水平速度（基于实测时间差），
 * 不再硬编码 0.05s/tick，对事件频率波动更稳健。
 */
public final class BoatSpeedCheck implements Check {

    private final AntiCheatPlugin plugin;

    public BoatSpeedCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "boat-speed";
    }

    @Override
    public void handleMove(Player player, TrackedPlayer tracked, CheckData data) {
        if (!plugin.isBoatSpeedCheckEnabled()) return;
        if (data.isZeroDelta() || data.isTeleporting()) return;
        if (!player.isInsideVehicle()) return;
        if (!(player.getVehicle() instanceof Boat)) return;

        double speed = data.getHorizontalSpeed();
        if (speed > plugin.getMaxBoatSpeed()) {
            plugin.getPunishmentManager().handleViolation(
                    player, tracked, String.format("异常船速 (%.1f m/s)", speed));
        }
    }
}
