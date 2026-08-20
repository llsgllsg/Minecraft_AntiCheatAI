package com.gfish.anticheat.check;

import org.bukkit.Location;

/**
 * 移动数据处理器（对应 Grim 的 processor 层）。
 * <p>
 * 每玩家一份。负责把原始移动事件（from/to）换算成速度等运动数据，
 * 并维护位置基线。速度用实测时间差而非硬编码 20TPS 计算，因此对事件
 * 被合并 / 频率波动的情况更稳健。
 */
public final class MovementProcessor {

    private Location lastPosition;
    private long lastTimeMs;

    /**
     * 处理一次移动事件，返回供检查使用的 {@link CheckData}。
     *
     * @param from            上一位置
     * @param to              当前位置
     * @param onGround        玩家是否在地面
     * @param teleporting     是否处于传送宽限期（此时速度置 0，避免把传送位移当速度）
     * @param velocityApplied 是否处于击退/加速宽限期
     */
    public CheckData processMove(Location from, Location to, boolean onGround,
                                 boolean teleporting, boolean velocityApplied) {
        long now = System.currentTimeMillis();
        double dt = (now - lastTimeMs) / 1000.0;
        if (dt <= 0) {
            dt = 1e-3;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        boolean zeroDelta = dx == 0 && dy == 0 && dz == 0;

        double horizontal = Math.sqrt(dx * dx + dz * dz) / dt;
        double vertical = dy / dt;

        // 传送 / 击退期间速度按 0 处理，避免把跨世界的位移误算成超高速度
        double reportedHorizontal = (teleporting || velocityApplied) ? 0 : horizontal;
        double reportedVertical = (teleporting || velocityApplied) ? 0 : vertical;

        lastPosition = to.clone();
        lastTimeMs = now;

        return new CheckData(from, to, reportedHorizontal, reportedVertical,
                onGround, zeroDelta, teleporting, velocityApplied);
    }

    /** 重置位置基线（传送 / 速度变化后调用），避免下一帧把旧基线当速度来源。 */
    public void reset(Location location) {
        lastPosition = location.clone();
        lastTimeMs = System.currentTimeMillis();
    }
}
