package com.gfish.anticheat.core.platform;

/**
 * 平台无关的位置 + 朝向快照（对应 Bukkit 的 {@code Location}）。
 * <p>
 * 刻意不含世界：世界身份单独由 {@link WorldKey} 表示，这样内核不必知道
 * 各平台怎么表示世界。yaw/pitch 用 float 保存 —— Bukkit 的
 * {@code Location#getYaw()} 就是 float，模组端也照此收窄，两端编码才一致。
 */
public final class Pos {

    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    public Pos(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /**
     * 方块坐标的取整方式，必须与 Bukkit 的 {@code Location#getBlock()} 一致
     * （向下取整，不是四舍五入）。
     */
    public static int toBlockCoord(double v) {
        return (int) Math.floor(v);
    }

    /** 与 Bukkit 的 {@code loc.clone().subtract(0, 0.1, 0)} 等价，用于探测脚下方块。 */
    public int belowBlockY() {
        return toBlockCoord(y - 0.1);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", x, y, z);
    }
}
