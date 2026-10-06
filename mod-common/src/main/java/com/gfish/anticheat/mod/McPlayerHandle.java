package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.platform.GameModeKind;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.platform.Pos;
import com.gfish.anticheat.core.platform.StatusEffect;
import com.gfish.anticheat.core.platform.WorldKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.GameType;

import java.util.UUID;

/** {@link PlayerHandle} 的 Mojang 侧实现。Fabric 与 NeoForge 共用。 */
public final class McPlayerHandle implements PlayerHandle {

    /** deepguard.admin 回退到原版 2 级（GAMEMASTERS，即 op）。 */
    private static final int ADMIN_COMMAND_LEVEL = 2;
    /**
     * deepguard.bypass 回退到 4 级（OWNERS，专用服务器控制台级）。
     * <p>
     * 必须取最高等级而不是最低：bypass 是"跳过全部检测"，放宽成 0/1 级
     * 会让所有玩家都能绕过反作弊，比不可用危险得多。
     */
    private static final int BYPASS_COMMAND_LEVEL = 4;

    private final ServerPlayer player;
    private final WorldKey world;

    public McPlayerHandle(ServerPlayer player) {
        this.player = player;
        this.world = new WorldKey(player.level());
    }

    /** 底层的 Mojang 对象，供 Mixin 与平台实现使用。 */
    public ServerPlayer mc() {
        return player;
    }

    @Override
    public UUID uuid() {
        return player.getUUID();
    }

    /**
     * 游戏档案名，对应 Bukkit 的 {@code Player#getName}。
     * <p>
     * ⚠️ 不能用 {@code getDisplayName()} 或任何显示名 —— 那是可被改的，
     * 玩家改个名就能让处罚命令打到不存在的目标上，等于绕过处罚。
     */
    @Override
    public String name() {
        // authlib 9 起 GameProfile 是 record，取值方法叫 name() 而不是 getName()
        return player.getGameProfile().name();
    }

    @Override
    public Pos pos() {
        return new Pos(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
    }

    @Override
    public WorldKey world() {
        return world;
    }

    @Override
    public boolean onGround() {
        return player.onGround();
    }

    @Override
    public boolean gliding() {
        return player.isFallFlying();
    }

    /** 原版没有 {@code Player#isFlying()}，飞行状态在 abilities 里。 */
    @Override
    public boolean flying() {
        return player.getAbilities().flying;
    }

    @Override
    public boolean inWater() {
        return player.isInWater();
    }

    @Override
    public boolean inLava() {
        return player.isInLava();
    }

    @Override
    public boolean insideVehicle() {
        return player.isPassenger();
    }

    /**
     * 26.2 的各类船都归在 {@code AbstractBoat} 下（26.x 新增了 Raft，
     * 它同样是 AbstractBoat 的子类），所以这里比 instanceof Boat 更贴合当前版本。
     */
    @Override
    public boolean inBoat() {
        return player.getVehicle() instanceof AbstractBoat;
    }

    @Override
    public boolean sprinting() {
        return player.isSprinting();
    }

    @Override
    public GameModeKind gameMode() {
        return gameModeOf(player.gameMode());
    }

    @Override
    public double velocityX() {
        return player.getDeltaMovement().x;
    }

    @Override
    public double velocityY() {
        return player.getDeltaMovement().y;
    }

    @Override
    public double velocityZ() {
        return player.getDeltaMovement().z;
    }

    @Override
    public String vehicleTypeKey() {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return "";
        }
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
        return key == null ? "" : key.toString();
    }

    @Override
    public boolean hasEffect(StatusEffect effect) {
        return switch (effect) {
            case LEVITATION -> player.hasEffect(MobEffects.LEVITATION);
            case SLOW_FALLING -> player.hasEffect(MobEffects.SLOW_FALLING);
        };
    }

    /**
     * 权限判定。
     * <p>
     * 没有权限插件时回退到原版命令等级 —— 两个加载器上都是同一套 API，
     * 所以内核只需要一份判定逻辑。装了就由权限插件接管的加载器（Paper 侧）
     * 走各自实现。
     */
    @Override
    public boolean hasPermission(String node) {
        return switch (node) {
            case "deepguard.admin" -> hasCommandLevel(ADMIN_COMMAND_LEVEL);
            case "deepguard.bypass" -> hasCommandLevel(BYPASS_COMMAND_LEVEL);
            default -> false;
        };
    }

    private boolean hasCommandLevel(int level) {
        return player.permissions().hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }

    /** {@code §} 传统颜色码由客户端直接渲染，三端表现一致。 */
    @Override
    public void sendMessage(String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    public static GameModeKind gameModeOf(GameType type) {
        if (type == GameType.CREATIVE) return GameModeKind.CREATIVE;
        if (type == GameType.ADVENTURE) return GameModeKind.ADVENTURE;
        if (type == GameType.SPECTATOR) return GameModeKind.SPECTATOR;
        return GameModeKind.SURVIVAL;
    }
}
