package com.gfish.anticheat.core.check;

import com.gfish.anticheat.ExemptionType;
import com.gfish.anticheat.core.DeepGuardCore;
import com.gfish.anticheat.core.TrackedPlayer;
import com.gfish.anticheat.core.platform.BlockProbe;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.platform.Pos;
import com.gfish.anticheat.core.platform.StatusEffect;

/**
 * 飞行 / 悬空检测（原 checkFly）。
 * <p>
 * 通过"是否具备合法滞空手段"的判定来累计可疑 tick；达到阈值后交给
 * {@code PunishmentManager} 处理。
 */
public final class FlyCheck implements Check {

    /** 累计多少次可疑 tick 触发一次处罚（20 TPS 下约 3 秒）。 */
    private static final int FLY_WARNINGS_BEFORE_PUNISH = 60;

    private final DeepGuardCore core;

    public FlyCheck(DeepGuardCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "fly";
    }

    @Override
    public void handleMove(PlayerHandle player, TrackedPlayer tracked, CheckData data) {
        if (!core.config().flyCheck) return;
        // 原地/旋转、传送宽限、击退宽限不参与判定
        if (data.isZeroDelta() || data.isTeleporting() || data.isVelocityApplied()) return;
        if (tracked.isExemptAny(ExemptionType.TELEPORT, ExemptionType.VELOCITY)) return;

        // 注意这四种 return 都发生在计数之前，且**不清零已有计数** ——
        // 与 Paper 版一致，改了会让"豁免期间攒下的警告数"行为不同。
        if (isLegalAirborne(player, data.getTo())) {
            tracked.setFlyWarnings(0);
            return;
        }

        int warnings = tracked.getFlyWarnings() + 1;
        tracked.setFlyWarnings(warnings);
        if (warnings >= FLY_WARNINGS_BEFORE_PUNISH) {
            tracked.setFlyWarnings(0);
            core.punishment().handleViolation(player, tracked, "飞行/悬空");
        }
    }

    /** 是否具备合法滞空手段。 */
    private boolean isLegalAirborne(PlayerHandle player, Pos to) {
        if (player.flying() || player.gliding() || player.insideVehicle()
                || player.inWater() || player.inLava() || player.onGround()
                || player.hasEffect(StatusEffect.LEVITATION)
                || player.hasEffect(StatusEffect.SLOW_FALLING)) {
            return true;
        }

        // 脚下 0.1 格处有方块且是实心 —— 站在台阶/半砖边缘时 onGround 会抖，
        // 这一条把它们捞回来。两个谓词都必须读，缺一个就会把流体或非实心方块算成地面。
        BlockProbe blocks = core.platform().blocks(player.world());
        int bx = Pos.toBlockCoord(to.x());
        int by = to.belowBlockY();
        int bz = Pos.toBlockCoord(to.z());
        return !blocks.isAir(bx, by, bz) && blocks.isSolid(bx, by, bz);
    }
}
