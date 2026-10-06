package com.gfish.anticheat.core.check;

import com.gfish.anticheat.core.platform.Pos;

import java.util.function.LongSupplier;

/**
 * 移动数据处理器（对应 Grim 的 processor 层）。每玩家一份。
 * <p>
 * 把原始移动事件（from/to）换算成速度，速度用<b>实测墙上时间差</b>而非硬编码
 * 20TPS 计算，因此对事件被合并 / 频率波动的情况更稳健。
 * <p>
 * ⚠️ 因为 {@code dt} 取的是相邻两次事件的墙上时间差，事件<b>频率</b>直接决定
 * 算出来的速度：漏发事件 → dt 偏大 → 速度偏低。所以各平台必须逐字复刻
 * Bukkit 的事件发射逻辑（含死区过滤），否则模组端的速度会与 Paper 系统性偏离。
 */
public final class MovementProcessor {

    private final LongSupplier clock;
    private long lastTimeMs;

    /** 默认用墙上时钟 —— 与 Paper 版行为一致；测试可注入假时钟。 */
    public MovementProcessor() {
        this(System::currentTimeMillis);
    }

    public MovementProcessor(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 处理一次移动事件，返回供检查使用的 {@link CheckData}。
     *
     * @param teleporting     是否处于传送宽限期（此时速度置 0，避免把传送位移当速度）
     * @param velocityApplied 是否处于击退/加速宽限期
     */
    public CheckData processMove(Pos from, Pos to, boolean onGround,
                                 boolean teleporting, boolean velocityApplied) {
        long now = clock.getAsLong();
        double dt = (now - lastTimeMs) / 1000.0;
        if (dt <= 0) {
            dt = 1e-3;
        }

        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        boolean zeroDelta = dx == 0 && dy == 0 && dz == 0;

        double horizontal = Math.sqrt(dx * dx + dz * dz) / dt;
        double vertical = dy / dt;

        // 传送 / 击退期间速度按 0 处理，避免把跨世界的位移误算成超高速度
        double reportedHorizontal = (teleporting || velocityApplied) ? 0 : horizontal;
        double reportedVertical = (teleporting || velocityApplied) ? 0 : vertical;

        lastTimeMs = now;

        return new CheckData(from, to, reportedHorizontal, reportedVertical,
                onGround, zeroDelta, teleporting, velocityApplied);
    }

    /** 重置时间基线（传送 / 速度变化后调用），避免下一帧拿旧基线算出巨大速度。 */
    public void reset() {
        lastTimeMs = clock.getAsLong();
    }
}
