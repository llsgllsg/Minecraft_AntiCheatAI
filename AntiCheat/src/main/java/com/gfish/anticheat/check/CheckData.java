package com.gfish.anticheat.check;

import org.bukkit.Location;

/**
 * 一次移动事件经 {@link MovementProcessor} 处理后的只读快照（对应 Grim 的 CheckData）。
 * <p>
 * 所有检查共享同一份计算好的运动数据，避免每个检查重复计算、也避免各自
 * 维护不一致的状态。
 */
public final class CheckData {

    private final Location from;
    private final Location to;
    private final double horizontalSpeed;   // 格/秒（水平分量）
    private final double verticalSpeed;     // 格/秒（Y 分量）
    private final boolean onGround;
    private final boolean zeroDelta;        // 位置未变化（仅旋转 / 原地）
    private final boolean teleporting;      // 处于传送宽限期
    private final boolean velocityApplied;  // 处于击退/加速宽限期

    public CheckData(Location from, Location to, double horizontalSpeed, double verticalSpeed,
                     boolean onGround, boolean zeroDelta, boolean teleporting, boolean velocityApplied) {
        this.from = from;
        this.to = to;
        this.horizontalSpeed = horizontalSpeed;
        this.verticalSpeed = verticalSpeed;
        this.onGround = onGround;
        this.zeroDelta = zeroDelta;
        this.teleporting = teleporting;
        this.velocityApplied = velocityApplied;
    }

    public Location getFrom() {
        return from;
    }

    public Location getTo() {
        return to;
    }

    public double getHorizontalSpeed() {
        return horizontalSpeed;
    }

    public double getVerticalSpeed() {
        return verticalSpeed;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public boolean isZeroDelta() {
        return zeroDelta;
    }

    public boolean isTeleporting() {
        return teleporting;
    }

    public boolean isVelocityApplied() {
        return velocityApplied;
    }
}
