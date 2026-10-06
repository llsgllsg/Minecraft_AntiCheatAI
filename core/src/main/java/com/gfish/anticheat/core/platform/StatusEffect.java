package com.gfish.anticheat.core.platform;

/**
 * 内核关心的状态效果 —— 这两个是 FlyCheck 认可的"合法滞空"来源。
 * <p>
 * 刻意不用字符串或平台枚举：各平台自己映射到 {@code MobEffects.LEVITATION}
 * 或 {@code PotionEffectType.LEVITATION}，内核只认这里的常量。
 */
public enum StatusEffect {
    LEVITATION,
    SLOW_FALLING
}
