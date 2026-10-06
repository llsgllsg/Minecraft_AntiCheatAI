package com.gfish.anticheat.core.platform;

/**
 * 世界标识，只用于相等比较（SpeedCheck 检测到换世界要重置速度基线）。
 * <p>
 * 各平台把自己的世界对象直接包进来即可（Paper 传 {@code World}，模组传
 * {@code ServerLevel} 或它的 {@code ResourceKey}），内核不关心内容是什么，
 * 因此这里是 {@code Object} 而不是一堆平台子类。
 */
public final class WorldKey {

    private final Object identity;

    public WorldKey(Object identity) {
        if (identity == null) {
            throw new IllegalArgumentException("identity 不能为 null");
        }
        this.identity = identity;
    }

    /**
     * 取出被包裹的平台世界对象。
     * <p>
     * 只有平台实现该调用它（模组侧要拿回 {@code ServerLevel} 去建方块查询器）。
     * 内核代码不许碰这里 —— 碰了就丧失了平台无关性。
     */
    public Object identity() {
        return identity;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorldKey other && identity.equals(other.identity);
    }

    @Override
    public int hashCode() {
        return identity.hashCode();
    }

    @Override
    public String toString() {
        return "WorldKey[" + identity + "]";
    }
}
