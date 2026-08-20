package com.gfish.anticheat.check;

import com.gfish.anticheat.AntiCheatPlugin;
import com.gfish.anticheat.ExemptionType;
import com.gfish.anticheat.TrackedPlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * 飞行 / 悬空检测（原 checkFly，拆为独立检查）。
 * <p>
 * 通过"是否具备合法滞空手段"的判定来累计可疑 tick；达到阈值后交给
 * {@link com.gfish.anticheat.PunishmentManager} 处理。
 */
public final class FlyCheck implements Check {

    /** 累计多少次可疑 tick 触发一次处罚（20 TPS 下约 3 秒）。 */
    private static final int FLY_WARNINGS_BEFORE_PUNISH = 60;

    private final AntiCheatPlugin plugin;

    public FlyCheck(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "fly";
    }

    @Override
    public void handleMove(Player player, TrackedPlayer tracked, CheckData data) {
        if (!plugin.isFlyCheckEnabled()) return;
        // 原地/旋转、传送宽限、击退宽限不参与判定
        if (data.isZeroDelta() || data.isTeleporting() || data.isVelocityApplied()) return;
        if (tracked.isExemptAny(ExemptionType.TELEPORT, ExemptionType.VELOCITY)) return;

        boolean legal = player.isFlying() || player.isGliding() ||
                player.isInsideVehicle() ||
                player.isInWater() || player.isInLava() ||
                player.isOnGround() ||
                player.hasPotionEffect(PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        Block below = data.getTo().clone().subtract(0, 0.1, 0).getBlock();
        if (!below.getType().isAir() && below.getType().isSolid()) {
            legal = true;
        }

        if (!legal) {
            int warnings = tracked.getFlyWarnings() + 1;
            tracked.setFlyWarnings(warnings);
            if (warnings >= FLY_WARNINGS_BEFORE_PUNISH) {
                tracked.setFlyWarnings(0);
                plugin.getPunishmentManager().handleViolation(player, tracked, "飞行/悬空");
            }
        } else {
            tracked.setFlyWarnings(0);
        }
    }
}
