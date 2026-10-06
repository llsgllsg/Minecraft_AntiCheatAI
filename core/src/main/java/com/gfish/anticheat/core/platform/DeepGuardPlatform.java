package com.gfish.anticheat.core.platform;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 平台门面 —— 内核与外界之间的唯一出口。
 * <p>
 * 三个平台（Paper / Fabric / NeoForge）各实现一份。内核代码里不允许出现任何
 * 平台类型；所有"这只有平台才知道"的事情都收敛到这个接口。
 */
public interface DeepGuardPlatform {

    DgLogger logger();

    /** 当前在线玩家。三端都必须在主线程上调用。 */
    List<PlayerHandle> onlinePlayers();

    /** 按 UUID 找在线玩家；不在线返回 null。 */
    PlayerHandle playerByUuid(UUID uuid);

    /** 按名字找在线玩家；找不到返回 null。 */
    PlayerHandle playerByName(String name);

    /**
     * 把任务排到主线程执行。
     * <p>
     * AI 推理是异步的，回来时必须切回主线程才能碰世界状态 —— Paper 走
     * Bukkit 调度器，模组走 {@code MinecraftServer#execute}。
     */
    void runOnMainThread(Runnable task);

    /** 以控制台身份执行一条原版命令（处罚用）。 */
    void dispatchConsoleCommand(String command);

    ConfigSource config();

    /** 插件/模组的数据目录；{@code violations/} 会建在这下面。 */
    Path dataFolder();

    /**
     * 内置的默认配置文件内容（交给平台写进数据目录），拿不到返回 null。
     * <p>
     * 三端共用同一份 {@code config.yml}，由构建期从 Paper 模块拷过来，
     * 所以默认值天然一致，不会出现"Fabric 的默认速度和 Paper 不一样"。
     */
    InputStream defaultConfigResource();

    /** 取某个世界的方块查询器。 */
    BlockProbe blocks(WorldKey world);
}
