package com.gfish.anticheat.fabric;

import com.gfish.anticheat.mod.McCommandRegistrar;
import com.gfish.anticheat.mod.McPlatform;
import com.gfish.anticheat.mod.ModRuntime;
import com.gfish.anticheat.mod.Slf4jDgLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * DeepGuard 的 Fabric 服务端入口（对应 Paper 版的 {@code AntiCheatPlugin}）。
 * <p>
 * 这里只做"接线"：把 Fabric 的生命周期事件翻译成 {@link ModRuntime} 的调用。
 * 检测逻辑全在 core，与 NeoForge 共用同一份。
 */
public final class DeepGuardFabric implements ModInitializer {

    public static final String MOD_ID = "deepguard";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** jar 内默认配置的路径；构建期从 Paper 模块的 config.yml 拷过来。 */
    private static final String DEFAULT_CONFIG_RESOURCE = "/deepguard/config.yml";

    private static volatile ModRuntime runtime;

    @Override
    public void onInitialize() {
        LOGGER.info("DeepGuard (Fabric) 正在初始化");

        ServerLifecycleEvents.SERVER_STARTING.register(DeepGuardFabric::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ModRuntime current = runtime;
            if (current != null) {
                current.core().shutdown();
            }
            runtime = null;
            ModRuntime.install(null);
        });

        ServerPlayerEvents.JOIN.register(player -> {
            if (runtime != null) runtime.onPlayerJoin(player);
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            if (runtime != null) runtime.onPlayerLeave(player);
        });
        // 重生 / 换维度会把 ServerPlayer 换成新实例。内核每 tick 的 getOrCreate 也会
        // 刷新 handle，所以这里即使漏了也能自愈；补上是为了让发射器基线立刻归位。
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            if (runtime != null) runtime.onPlayerReplaced(newPlayer);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (runtime != null) runtime.onServerTick();
        });

        McCommandRegistrar registrar = new McCommandRegistrar(() -> runtime);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> registrar.register(dispatcher));
    }

    private static void start(MinecraftServer server) {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        McPlatform platform = new McPlatform(
                server,
                new Slf4jDgLogger(LOGGER),
                configDir,
                () -> DeepGuardFabric.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE));
        ModRuntime created = new ModRuntime(platform);
        runtime = created;
        ModRuntime.install(created);
        LOGGER.info("DeepGuard (Fabric) 已启用，配置目录: " + configDir);
    }
}
