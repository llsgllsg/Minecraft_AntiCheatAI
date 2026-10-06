package com.gfish.anticheat.core.platform;

/** 游戏模式。对应 Bukkit 的 {@code GameMode}，只保留内核用得到的区分度。 */
public enum GameModeKind {
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR
}
