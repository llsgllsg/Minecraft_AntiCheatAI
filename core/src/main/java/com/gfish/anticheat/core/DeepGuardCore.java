package com.gfish.anticheat.core;

import com.gfish.anticheat.BehaviorImageBuilder;
import com.gfish.anticheat.BehaviorRecorder;
import com.gfish.anticheat.ExemptionType;
import com.gfish.anticheat.core.check.BoatSpeedCheck;
import com.gfish.anticheat.core.check.Check;
import com.gfish.anticheat.core.check.CheckData;
import com.gfish.anticheat.core.check.FlyCheck;
import com.gfish.anticheat.core.check.SpeedCheck;
import com.gfish.anticheat.core.command.DeepGuardCommands;
import com.gfish.anticheat.core.config.DeepGuardConfig;
import com.gfish.anticheat.core.platform.ApBonusProvider;
import com.gfish.anticheat.core.platform.DeepGuardPlatform;
import com.gfish.anticheat.core.platform.GameModeKind;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.platform.Pos;
import com.gfish.anticheat.core.spi.AiEngine;
import com.gfish.anticheat.core.spi.UpdateService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * DeepGuard 的平台无关内核（对应 Paper 版的 {@code AntiCheatPlugin}）。
 * <p>
 * 只负责：生命周期（TrackedPlayer 的创建/销毁）、调度（录制、检查、AI 扫描）、
 * 事件分发。具体检测逻辑在 {@code check} 包，处罚在 {@link PunishmentManager}，
 * 与外界的一切交互都经 {@link DeepGuardPlatform}。
 * <p>
 * 三端（Paper / Fabric / NeoForge）共用这一个内核 —— 各平台只做两件事：
 * 把事件翻译成下面这些 {@code on*} 调用，以及实现 {@code platform} 包里的 SPI。
 */
public final class DeepGuardCore {

    /** AI 扫描周期（tick）。1200 tick = 60 秒。 */
    public static final int AI_SCAN_INTERVAL_TICKS = 1200;
    /** 少于这么多 tick 就放弃分析 —— 样本太短，模型输出没有意义。 */
    private static final int MIN_AI_TICKS = 100;

    private final DeepGuardPlatform platform;
    private final ApBonusProvider apBonus;
    private final LongSupplier clock;
    private final PunishmentManager punishment;
    private final Map<UUID, TrackedPlayer> trackedPlayers = new HashMap<>();
    private final List<Check> checks = new ArrayList<>();

    private volatile DeepGuardConfig config;
    private AiEngine aiEngine;
    private volatile UpdateService updateService;
    private int tickCounter;
    private volatile boolean running = true;

    public DeepGuardCore(DeepGuardPlatform platform, ApBonusProvider apBonus) {
        this(platform, apBonus, System::currentTimeMillis);
    }

    public DeepGuardCore(DeepGuardPlatform platform, ApBonusProvider apBonus, LongSupplier clock) {
        this.platform = platform;
        this.apBonus = apBonus;
        this.clock = clock;
        this.config = DeepGuardConfig.load(platform.config());
        this.punishment = new PunishmentManager(platform, this::config, clock);

        checks.add(new FlyCheck(this));
        checks.add(new BoatSpeedCheck(this));
        checks.add(new SpeedCheck(this));
    }

    public DeepGuardPlatform platform() {
        return platform;
    }

    public DeepGuardConfig config() {
        return config;
    }

    public PunishmentManager punishment() {
        return punishment;
    }

    public ApBonusProvider apBonus() {
        return apBonus;
    }

    public LongSupplier clock() {
        return clock;
    }

    /** 平台注入的版本检查/模型下载实现；平台不支持时为 null。 */
    public UpdateService updateService() {
        return updateService;
    }

    public void setUpdateService(UpdateService updateService) {
        this.updateService = updateService;
    }

    /** 已跟踪的玩家状态；没有就返回 null（区别于 {@link #getOrCreate} 的补建行为）。 */
    public TrackedPlayer trackedOrNull(UUID uuid) {
        return trackedPlayers.get(uuid);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 平台启动时调用一次。 */
    public void start() {
        platform.logger().info("DeepGuard 内核已启动（" + checks.size() + " 个检查）");
    }

    /** 平台关停时调用，阻止已在途的异步 AI 回调继续处罚。 */
    public void shutdown() {
        running = false;
        trackedPlayers.clear();
    }

    /**
     * 重载配置。
     * <p>
     * ⚠️ 只换 config 对象：<b>不重建 checks、不重启任何计时</b>。
     * 重建会把各检查的运行时状态（速度基线、采样计时）清掉，等于给所有玩家
     * 一次免费重置。
     */
    public void reloadConfig() {
        this.config = DeepGuardConfig.load(platform.config());
    }

    /**
     * 装载 AI 引擎并做通道兼容性检查。
     *
     * @return 是否装载成功
     */
    public boolean installAiEngine(AiEngine engine) {
        if (engine == null) {
            this.aiEngine = null;
            return false;
        }
        if (!engine.isLoaded()) {
            platform.logger().warn("AI 模型加载失败");
            this.aiEngine = null;
            return false;
        }

        int modelChannels = engine.modelChannels();
        if (modelChannels > BehaviorImageBuilder.CHANNELS) {
            // 模型比代码新：特征通道不够用，无法满足
            platform.logger().severe("AI 模型需要 " + modelChannels + " 个通道，"
                    + "当前特征编码只有 " + BehaviorImageBuilder.CHANNELS
                    + " 个。请升级。AI 检测已停用。");
            this.aiEngine = null;
            return false;
        }
        if (modelChannels > 0 && modelChannels < BehaviorImageBuilder.CHANNELS) {
            // 模型比代码旧：向下兼容。新通道都追加在末尾，
            // 只取前 N 个通道即等价于旧版编码，所以旧模型照常可用。
            platform.logger().info("AI 模型加载成功（旧版 " + modelChannels + " 通道模型，"
                    + "向下兼容模式：只使用前 " + modelChannels + " 个特征通道）");
        } else {
            platform.logger().info("AI 模型加载成功（" + BehaviorImageBuilder.CHANNELS + " 通道）");
        }
        this.aiEngine = engine;
        return true;
    }

    public boolean isAiReady() {
        return aiEngine != null;
    }

    // ------------------------------------------------------------------
    // 每 tick 调度（由平台在服务端 tick 末尾驱动）
    // ------------------------------------------------------------------

    /**
     * 服务端每 tick 调用一次。
     * <p>
     * 录制、逐 tick 检查、AI 扫描周期全部在这里推进 —— 三端相位因此天然一致。
     * （Paper 版曾用一个独立的 1200 tick 调度器跑 AI 扫描，相位与逐 tick 任务不同，
     * 这里统一成同一个计数器。）
     */
    public void onServerTick() {
        if (!running) return;

        for (PlayerHandle player : platform.onlinePlayers()) {
            TrackedPlayer tracked = getOrCreate(player);
            tracked.recordTick();
            for (Check check : checks) {
                check.tick(player, tracked);
            }
        }

        if (aiEngine != null && ++tickCounter >= AI_SCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            runAiScan();
        }
    }

    // ------------------------------------------------------------------
    // 玩家生命周期
    // ------------------------------------------------------------------

    public void onPlayerJoin(PlayerHandle player) {
        getOrCreate(player);
    }

    public void onPlayerQuit(UUID uuid) {
        trackedPlayers.remove(uuid);
    }

    /** 玩家死亡重生 / 换维度后实例被替换，必须换掉缓存的 handle。 */
    public void onPlayerReplaced(PlayerHandle player) {
        TrackedPlayer tracked = trackedPlayers.get(player.uuid());
        if (tracked != null) {
            tracked.refresh(player);
        }
    }

    /**
     * 取玩家状态；没有就补建。
     * <p>
     * 会为没走过 join 的玩家补建 —— 有些平台（或某些加载顺序）不会为
     * 每个在线玩家都发一次 join 事件，漏了就永远不检测。
     */
    public TrackedPlayer getOrCreate(PlayerHandle player) {
        TrackedPlayer tracked = trackedPlayers.get(player.uuid());
        if (tracked == null) {
            tracked = new TrackedPlayer(player, clock);
            trackedPlayers.put(player.uuid(), tracked);
        } else {
            // 实例可能已被替换（重生 / 换维度），每次都用最新的
            tracked.refresh(player);
        }
        return tracked;
    }

    // ------------------------------------------------------------------
    // 事件入口（平台翻译后调用）
    // ------------------------------------------------------------------

    /**
     * 移动事件。
     * <p>
     * ⚠️ 平台必须<b>逐字复刻 Bukkit 的事件发射逻辑</b>（含死区过滤、以及
     * 骑船走 {@code handleMoveVehicle}）：{@code MovementProcessor} 用相邻两次
     * 事件的墙上时间差算速度，事件多发或漏发都会让模组端的速度与 Paper 系统性偏离。
     */
    public void onMove(PlayerHandle player, Pos from, Pos to, boolean onGround) {
        if (!running) return;
        if (isBypassed(player)) return;

        TrackedPlayer tracked = trackedPlayers.get(player.uuid());
        if (tracked == null) return;

        CheckData data = tracked.getProcessor().processMove(
                from, to, onGround,
                tracked.isExempt(ExemptionType.TELEPORT),
                tracked.isExempt(ExemptionType.VELOCITY));

        for (Check check : checks) {
            check.handleMove(player, tracked, data);
        }
    }

    public void onTeleport(PlayerHandle player) {
        TrackedPlayer tracked = trackedPlayers.get(player.uuid());
        if (tracked != null) {
            tracked.onTeleport();
        }
    }

    public void onVelocity(PlayerHandle player) {
        TrackedPlayer tracked = trackedPlayers.get(player.uuid());
        if (tracked != null) {
            tracked.onVelocity();
        }
    }

    public void onBlockPlace(PlayerHandle player) {
        TrackedPlayer tracked = trackedPlayers.get(player.uuid());
        if (tracked != null) {
            tracked.markPlacing();
        }
    }

    // ------------------------------------------------------------------
    // 跳过检测的判定
    // ------------------------------------------------------------------

    /** 移动事件一律跳过的玩家：非生存模式、有 bypass 权限、Geyser 基岩版。 */
    private boolean isBypassed(PlayerHandle player) {
        GameModeKind mode = player.gameMode();
        if (mode == GameModeKind.CREATIVE || mode == GameModeKind.SPECTATOR) return true;
        if (player.hasPermission("deepguard.bypass")) return true;
        return isExempted(player);
    }

    /**
     * Geyser 基岩版玩家豁免，靠 UUID 版本号识别：version 3 = 基岩版，4 = Java 版。
     * <p>
     * 基岩版的移动包语义与 Java 版不同，进来必然误报，所以整类跳过。
     */
    private boolean isExempted(PlayerHandle player) {
        return config.geyserBypassEnabled && player.uuid().version() == 3;
    }

    // ------------------------------------------------------------------
    // AI 检测
    // ------------------------------------------------------------------

    private void runAiScan() {
        for (PlayerHandle player : platform.onlinePlayers()) {
            if (isExempted(player)) continue;
            if (player.hasPermission("deepguard.bypass")) continue;
            analyzeAsync(player, probs -> onAiScanResult(player, probs));
        }
    }

    private void onAiScanResult(PlayerHandle player, float[] probs) {
        float cheatProb = probs[1];
        if (cheatProb >= config.autoPunishThreshold) {
            TrackedPlayer tracked = trackedPlayers.get(player.uuid());
            if (tracked != null) {
                punishment.handleViolation(player, tracked, "AI 定时检测：高度疑似作弊");
            }
        } else if (cheatProb >= config.alertThreshold) {
            String msg = String.format(Locale.ROOT, "§e[AC] §c%s §7AI 定时检测：可疑行为 (%.1f%%)",
                    player.name(), cheatProb * 100);
            for (PlayerHandle p : platform.onlinePlayers()) {
                if (p.hasPermission("deepguard.admin")) {
                    p.sendMessage(msg);
                }
            }
            platform.logger().info(stripColor(msg));
        }
    }

    /**
     * 异步分析玩家最近的行为。
     * <p>
     * 推理在后台线程跑，回调<b>必须切回主线程</b>（回调会碰世界状态、发消息、踢人）。
     * <p>
     * 与 Paper 版相比多了一道"回调时玩家是否还在线"的检查：原版只判插件是否启用，
     * 玩家在推理期间退出也会照常处罚 —— 那条踢出命令必然失败，还会白写一条违规记录。
     */
    public void analyzeAsync(PlayerHandle player, Consumer<float[]> callback) {
        AiEngine engine = aiEngine;
        if (engine == null || !running) return;

        TrackedPlayer tracked = trackedPlayers.get(player.uuid());
        if (tracked == null) return;

        BehaviorRecorder.BehaviorTick[] ticks =
                tracked.getRecorder().getRecentTicks(config.analysisSeconds * 20);
        if (ticks.length < MIN_AI_TICKS) return;

        float[][][] image = BehaviorImageBuilder.buildImage(ticks);
        UUID uuid = player.uuid();

        CompletableFuture.supplyAsync(() -> engine.infer(image))
                .exceptionally(ex -> {
                    platform.logger().warn("AI 推理异常: " + ex.getMessage());
                    return null;
                })
                .thenAccept(probs -> {
                    if (probs == null || !running) return;
                    platform.runOnMainThread(() -> {
                        PlayerHandle current = platform.playerByUuid(uuid);
                        if (current == null) return; // 推理期间已离线
                        callback.accept(probs);
                    });
                });
    }

    /** 去掉 {@code §} 传统颜色码，用于写控制台日志。 */
    public static String stripColor(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 命令实现，供各平台注册命令时转发。 */
    public DeepGuardCommands commands() {
        return new DeepGuardCommands(this);
    }
}
