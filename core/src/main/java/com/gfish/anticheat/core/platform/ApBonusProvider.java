package com.gfish.anticheat.core.platform;

/**
 * 属性加成来源，用于 SpeedCheck 动态放宽允许速度。
 * <p>
 * Paper 版接 PlaceholderAPI（{@code %ap_moving:max%} 之类）；两个模组端没有
 * 等价物，直接返回 0 —— 于是允许速度退化成配置里的 {@code max-speed}，
 * 这正是"没有属性插件时的 Paper 行为"，不需要额外分支。
 */
public interface ApBonusProvider {

    /** 返回属性加成百分比（例如 25.0 表示放宽 25%）。拿不到就返回 0。 */
    double apBonusPercent(PlayerHandle player);
}
