package com.gfish.anticheat.core.check;

import com.gfish.anticheat.ExemptionType;
import com.gfish.anticheat.core.DeepGuardCore;
import com.gfish.anticheat.core.TrackedPlayer;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.platform.Pos;
import com.gfish.anticheat.core.platform.WorldKey;

import java.util.Locale;

/**
 * 水平速度检测（原 checkPlayerSpeed）。
 * <p>
 * 内部降频到 1 秒采样一次；传送 / 击退宽限期内重置基线，避免误判。
 * 允许速度可由属性加成（Paper 上是 PlaceholderAPI）动态放宽。
 */
public final class SpeedCheck implements Check {

    private static final long SAMPLE_INTERVAL_MS = 1000;

    private final DeepGuardCore core;

    public SpeedCheck(DeepGuardCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "speed";
    }

    @Override
    public void tick(PlayerHandle player, TrackedPlayer tracked) {
        if (!core.config().speedCheckEnabled) return;

        // 采样计时必须按玩家保存：本检查是所有玩家共用的单例，
        // 若计时放在检查自身字段上，每秒钟只会有一个玩家被真正采样。
        long now = core.clock().getAsLong();
        if (now - tracked.getLastSpeedSampleTime() < SAMPLE_INTERVAL_MS) {
            return;
        }
        tracked.setLastSpeedSampleTime(now);

        if (tracked.isExemptAny(ExemptionType.TELEPORT, ExemptionType.VELOCITY)) {
            tracked.setLastSpeedPos(null, null);
            return;
        }

        Pos current = player.pos();
        WorldKey world = player.world();
        Pos last = tracked.getLastSpeedPos();
        if (last == null || !world.equals(tracked.getLastSpeedWorld())) {
            tracked.setLastSpeedPos(current, world);
            return;
        }

        double dx = current.x() - last.x();
        double dz = current.z() - last.z();
        double speed = Math.sqrt(dx * dx + dz * dz); // 1 秒采样间隔，即格/秒

        double allowed = getMaxAllowedSpeed(player);
        if (speed > allowed) {
            String cmd = core.config().speedPunishCommand.replace("%player%", player.name());
            core.platform().dispatchConsoleCommand(cmd);
            core.platform().logger().info(String.format(Locale.ROOT,
                    "玩家 %s 速度过快 (%.2f > %.2f)，已执行速度处罚",
                    player.name(), speed, allowed));
        }

        tracked.setLastSpeedPos(current, world);
    }

    private double getMaxAllowedSpeed(PlayerHandle player) {
        double apBonus = core.apBonus().apBonusPercent(player);
        return core.config().maxSpeed * (1.0 + apBonus / 100.0);
    }
}
