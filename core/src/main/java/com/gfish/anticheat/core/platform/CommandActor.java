package com.gfish.anticheat.core.platform;

import java.util.List;

/**
 * 命令的执行者（玩家或控制台）。
 * <p>
 * 内核只认这些字符串层面的操作，不回传 Brigadier / Bukkit 的命令对象 ——
 * 否则 {@code net.minecraft.commands.CommandSourceStack} 会被钉进内核，
 * 而 Paper 模块的 Maven 编译根本拿不到 Mojang 类型。
 */
public interface CommandActor {

    /** 用于日志与 {@code %player%} 占位。控制台通常返回 "CONSOLE"。 */
    String name();

    boolean isAdmin();

    /**
     * 发送一行消息。字符串里的 {@code §} 传统颜色码由客户端直接渲染，
     * 三端表现一致，所以内核这边不做颜色抽象。
     */
    void sendMessage(String message);

    /** 在线玩家名，用于 tab 补全。控制台等没有上下文时返回空列表。 */
    default List<String> onlinePlayerNames() {
        return List.of();
    }
}
