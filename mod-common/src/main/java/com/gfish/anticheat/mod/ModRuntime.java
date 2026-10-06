package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.DeepGuardCore;
import com.gfish.anticheat.core.platform.ApBonusProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 两个模组共用的运行时：把平台事件翻译成内核调用。
 * <p>
 * Mixin 无法注入实例，所以这里留一个静态入口（{@link #get()}）——每个 JVM
 * 只会装一个加载器，不存在多实例问题。
 */
public final class ModRuntime {

    /**
     * 模组端没有 PlaceholderAPI 之类的属性加成来源，恒返回 0。
     * <p>
     * 于是 SpeedCheck 的允许速度退化成配置里的 {@code max-speed}，
     * 正是"Paper 上没装属性插件"的行为，不需要在内核里加分支。
     */
    private static final ApBonusProvider NO_AP_BONUS = player -> 0.0;

    private static volatile ModRuntime instance;

    private final McPlatform platform;
    private final DeepGuardCore core;
    private final Map<UUID, MoveEventEmitter> emitters = new HashMap<>();

    public ModRuntime(McPlatform platform) {
        this.platform = platform;
        this.core = new DeepGuardCore(platform, NO_AP_BONUS);
        // 模组端暂不接 ONNX（阶段 4 才有运行时下载 + 子类加载器桥接），
        // 此时 AI 扫描不跑，移动类检查照常工作。
        core.start();
    }

    public static ModRuntime get() {
        return instance;
    }

    public static void install(ModRuntime runtime) {
        instance = runtime;
    }

    public McPlatform platform() {
        return platform;
    }

    public DeepGuardCore core() {
        return core;
    }

    // ------------------------------------------------------------------
    // 生命周期（由各加载器的事件驱动）
    // ------------------------------------------------------------------

    public void onServerTick() {
        core.onServerTick();
    }

    public void onPlayerJoin(ServerPlayer player) {
        emitterFor(player).reset(player);
        core.onPlayerJoin(new McPlayerHandle(player));
    }

    public void onPlayerLeave(ServerPlayer player) {
        emitters.remove(player.getUUID());
        core.onPlayerQuit(player.getUUID());
    }

    /**
     * 玩家实例被替换（死亡重生 / 换维度）。
     * <p>
     * 不刷新的话，内核会一直读着旧的 {@code ServerPlayer}：位置停在上个维度、
     * 状态也全错，速度检测会持续误报。
     */
    public void onPlayerReplaced(ServerPlayer player) {
        emitterFor(player).reset(player);
        core.onPlayerReplaced(new McPlayerHandle(player));
    }

    // ------------------------------------------------------------------
    // 移动
    // ------------------------------------------------------------------

    /**
     * 一个移动包（玩家自身走 {@code handleMovePlayer}，骑乘走 {@code handleMoveVehicle}）。
     * <p>
     * 是否派发由 {@link MoveEventEmitter} 按 CraftBukkit 的死区规则决定 ——
     * 不是每个包都会到内核。
     */
    public void onMovePacket(ServerPlayer player, double toX, double toY, double toZ,
                             float toYaw, float toPitch, boolean onGround) {
        MoveEventEmitter.Emission emission =
                emitterFor(player).evaluate(player, toX, toY, toZ, toYaw, toPitch, onGround);
        if (emission == null) {
            return;
        }
        core.onMove(new McPlayerHandle(player), emission.from(), emission.to(), emission.onGround());
    }

    public void onBlockPlace(ServerPlayer player) {
        core.onBlockPlace(new McPlayerHandle(player));
    }

    /**
     * 传送：开豁免 + 重置发射器基线。
     * <p>
     * 顺序有意义 —— 豁免要在传送<b>生效前</b>开（与 Paper 的 PlayerTeleportEvent
     * 一致），基线则要在传送<b>生效后</b>才重置（与 CraftBukkit 的 resetPosition 一致），
     * 否则下一个包会相对旧基线算出一个巨大的位移。
     */
    public void onTeleportStart(ServerPlayer player) {
        core.onTeleport(new McPlayerHandle(player));
    }

    public void onTeleportEnd(ServerPlayer player) {
        emitterFor(player).reset(player);
    }

    /** 击退 / 速度被改（hurtMarked 路径）。 */
    public void onVelocity(ServerPlayer player) {
        core.onVelocity(new McPlayerHandle(player));
    }

    private MoveEventEmitter emitterFor(ServerPlayer player) {
        return emitters.computeIfAbsent(player.getUUID(), id -> new MoveEventEmitter());
    }
}
