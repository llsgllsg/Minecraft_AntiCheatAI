package com.gfish.anticheat.core.platform;

import java.util.UUID;

/**
 * 一个在线玩家的平台无关视图。
 * <p>
 * 所有方法都必须在**服务端主线程**上调用 —— 与 Bukkit 的玩家 API 约束一致，
 * 模组端同理（{@code ServerPlayer} 不是线程安全的）。
 * <p>
 * 方法命名刻意贴近 Bukkit 的 {@code Player}，这样从 Paper 版迁移检查逻辑时
 * 语义一一对应，不需要在脑子里做二次翻译。
 */
public interface PlayerHandle {

    UUID uuid();

    /**
     * 玩家名。必须是**游戏档案名**（Bukkit 的 {@code Player#getName}）。
     * 模组端要对应用 {@code getGameProfile().getName()}，而不是会被改的显示名。
     */
    String name();

    Pos pos();

    WorldKey world();

    boolean onGround();

    /** 鞘翅滑翔中。 */
    boolean gliding();

    /** 飞行状态。原版是 {@code abilities.flying}，Bukkit 上是 {@code isFlying()}。 */
    boolean flying();

    boolean inWater();

    boolean inLava();

    boolean insideVehicle();

    /**
     * 是否坐在「船」上。**必须由平台实现**：Paper 判 {@code instanceof Boat}，
     * 模组判 {@code AbstractBoat}（26.2 还多了 Raft）。
     * Paper 升到含 raft 的版本后这里必须重测。
     */
    boolean inBoat();

    boolean sprinting();

    GameModeKind gameMode();

    double velocityX();

    double velocityY();

    double velocityZ();

    /** 载具类型 key（如 {@code minecraft:boat}）；不在载具时为空串。只记录，不参与特征编码。 */
    String vehicleTypeKey();

    boolean hasEffect(StatusEffect effect);

    /** 权限节点判定。没有权限插件时回退到原版权限等级。 */
    boolean hasPermission(String node);

    /** 给该玩家发一行消息。{@code §} 传统颜色码由客户端直接渲染，三端一致。 */
    void sendMessage(String message);
}
