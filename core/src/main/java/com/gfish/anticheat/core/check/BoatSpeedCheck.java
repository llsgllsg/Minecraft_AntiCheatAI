package com.gfish.anticheat.core.check;

import com.gfish.anticheat.core.DeepGuardCore;
import com.gfish.anticheat.core.TrackedPlayer;
import com.gfish.anticheat.core.platform.PlayerHandle;

import java.util.Locale;

/**
 * 船速检测（原 checkBoatSpeed）。
 * <p>
 * 用 {@link MovementProcessor} 基于实测时间差算出的水平速度，不硬编码 0.05s/tick。
 * <p>
 * ⚠️ 本检查<b>没有冷却</b>，也<b>不检查 velocityApplied</b> —— 只看 teleporting。
 * 看似是疏漏，但这是线上现状，改动会让罚则节奏变化。
 * <p>
 * ⚠️ 模组端必须在 {@code handleMoveVehicle} 上也触发移动事件，否则骑船玩家的
 * 移动一个都进不来，这个检查等于不存在（而且单机冒烟测不出来）。
 */
public final class BoatSpeedCheck implements Check {

    private final DeepGuardCore core;

    public BoatSpeedCheck(DeepGuardCore core) {
        this.core = core;
    }

    @Override
    public String name() {
        return "boat-speed";
    }

    @Override
    public void handleMove(PlayerHandle player, TrackedPlayer tracked, CheckData data) {
        if (!core.config().boatSpeedCheck) return;
        if (data.isZeroDelta() || data.isTeleporting()) return;
        if (!player.insideVehicle()) return;
        if (!player.inBoat()) return;

        double speed = data.getHorizontalSpeed();
        if (speed > core.config().maxBoatSpeed) {
            core.punishment().handleViolation(player, tracked,
                    String.format(Locale.ROOT, "异常船速 (%.1f m/s)", speed));
        }
    }
}
