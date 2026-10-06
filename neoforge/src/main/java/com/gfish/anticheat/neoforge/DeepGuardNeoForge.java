package com.gfish.anticheat.neoforge;

import com.gfish.anticheat.mod.McCommandRegistrar;
import com.gfish.anticheat.mod.McPlatform;
import com.gfish.anticheat.mod.ModRuntime;
import com.gfish.anticheat.mod.Slf4jDgLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * DeepGuard 的 NeoForge 服务端入口（对应 Paper 版的 {@code AntiCheatPlugin}）。
 * <p>
 * 与 Fabric 版共用 core（检测逻辑）和 mod-common（PlayerHandle 实现、移动事件
 * 发射器、<b>全部 Mixin</b>、配置读取）。这里只接 NeoForge 独有的生命周期事件。
 * <p>
 * <b>刻意不用 NeoForge 的放置 / 击退原生事件</b>（{@code EntityPlaceEvent}、
 * {@code LivingKnockBackEvent}）：它们的语义与 Bukkit 不等价 —— 前者不保证和
 * {@code useItemOn} + {@code consumesAction()} 同频，后者覆盖面差得更多。
 * 两个模组共用同一份 Mixin，行为由构造保证一致，而不是靠逐条对齐事件语义。
 */
@Mod(value = DeepGuardNeoForge.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class DeepGuardNeoForge {

    public static final String MOD_ID = "deepguard";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** jar 内默认配置的路径；构建期从 Paper 模块的 config.yml 拷过来。 */
    private static final String DEFAULT_CONFIG_RESOURCE = "/deepguard/config.yml";

    private static volatile ModRuntime runtime;

    public DeepGuardNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("DeepGuard (NeoForge) 正在初始化");

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(this::onServerStarting);
        // AI 在 SERVER_STARTED 之后才起：它要下 87 MB 的 runtime，
        // 全程异步、不阻塞启动；就绪前移动类检查已经生效。
        bus.addListener(this::onServerStarted);
        bus.addListener(this::onServerStopped);
        bus.addListener(this::onPlayerLoggedIn);
        bus.addListener(this::onPlayerLoggedOut);
        bus.addListener(this::onPlayerClone);
        bus.addListener(this::onServerTick);
        bus.addListener(this::onRegisterCommands);
    }

    private void onServerStarting(ServerStartingEvent event) {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
        McPlatform platform = new McPlatform(
                event.getServer(),
                new Slf4jDgLogger(LOGGER),
                configDir,
                () -> DeepGuardNeoForge.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE));
        ModRuntime created = new ModRuntime(platform);
        runtime = created;
        ModRuntime.install(created);
        LOGGER.info("DeepGuard (NeoForge) 已启用，配置目录: " + configDir);
    }

    private void onServerStarted(ServerStartedEvent event) {
        ModRuntime current = runtime;
        if (current == null) {
            return;
        }
        String modVersion = ModList.get().getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        current.startAi(modVersion);
    }

    private void onServerStopped(ServerStoppedEvent event) {
        ModRuntime current = runtime;
        if (current != null) {
            current.core().shutdown();
        }
        runtime = null;
        ModRuntime.install(null);
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (runtime != null && event.getEntity() instanceof ServerPlayer player) {
            runtime.onPlayerJoin(player);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (runtime != null && event.getEntity() instanceof ServerPlayer player) {
            runtime.onPlayerLeave(player);
        }
    }

    /** 重生 / 换维度后实体实例被替换，内核里缓存的 handle 必须跟着换。 */
    private void onPlayerClone(PlayerEvent.Clone event) {
        if (runtime != null && event.getEntity() instanceof ServerPlayer player) {
            runtime.onPlayerReplaced(player);
        }
    }

    /**
     * 用服务端 tick 而不是 {@code PlayerTickEvent}。
     * <p>
     * {@code PlayerTickEvent} 是逐玩家的，采样相位与 Paper / Fabric 的
     * "每 tick 遍历全体玩家"不同 —— 相位差会让行为录制的时间戳分布与另外两端
     * 错开，进而影响特征图。统一走服务端 tick，三端相位一致。
     */
    private void onServerTick(ServerTickEvent.Post event) {
        if (runtime != null) {
            runtime.onServerTick();
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        new McCommandRegistrar(() -> runtime).register(event.getDispatcher());
    }
}
