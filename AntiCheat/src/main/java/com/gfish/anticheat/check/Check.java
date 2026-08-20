package com.gfish.anticheat.check;

import com.gfish.anticheat.TrackedPlayer;
import org.bukkit.entity.Player;

/**
 * 检查接口（对应 Grim 的 AbstractCheck）。
 * <p>
 * 每个检测一项独立的能力，插件按固定生命周期调度：
 * - {@link #handleMove}：每次位置变化的移动事件
 * - {@link #tick}：每 tick 调用（可按需内部降频）
 */
public interface Check {

    /** 检查名称，用于日志与配置定位。 */
    String name();

    /** 移动事件回调。 */
    default void handleMove(Player player, TrackedPlayer tracked, CheckData data) {
    }

    /** 每 tick 回调。 */
    default void tick(Player player, TrackedPlayer tracked) {
    }
}
