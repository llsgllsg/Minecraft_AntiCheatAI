package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.platform.BlockProbe;
import com.gfish.anticheat.core.platform.ConfigSource;
import com.gfish.anticheat.core.platform.DeepGuardPlatform;
import com.gfish.anticheat.core.platform.DgLogger;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.platform.WorldKey;
import com.gfish.anticheat.mod.config.FileConfigSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link DeepGuardPlatform} 的 Mojang 侧实现。Fabric 与 NeoForge 共用，
 * 差异（日志实现、配置目录）由各自的入口以构造参数喂进来。
 */
public final class McPlatform implements DeepGuardPlatform {

    private final MinecraftServer server;
    private final DgLogger logger;
    private final Path dataFolder;
    private final Path configFile;
    private final Supplier<InputStream> defaultConfig;
    private final ConfigSource fallbackConfig = FileConfigSource.empty();

    public McPlatform(MinecraftServer server, DgLogger logger, Path dataFolder,
                      Supplier<InputStream> defaultConfig) {
        this.server = server;
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.configFile = dataFolder.resolve("config.yml");
        this.defaultConfig = defaultConfig;
    }

    public MinecraftServer server() {
        return server;
    }

    @Override
    public DgLogger logger() {
        return logger;
    }

    @Override
    public List<PlayerHandle> onlinePlayers() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        List<PlayerHandle> out = new ArrayList<>(players.size());
        for (ServerPlayer p : players) {
            out.add(new McPlayerHandle(p));
        }
        return out;
    }

    @Override
    public PlayerHandle playerByUuid(UUID uuid) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        return p == null ? null : new McPlayerHandle(p);
    }

    @Override
    public PlayerHandle playerByName(String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p == null ? null : new McPlayerHandle(p);
    }

    /**
     * 排到服务端主线程。
     * <p>
     * {@code MinecraftServer} 本身就是那个主线程执行器（继承自
     * {@code BlockableEventLoop}，实现了 {@code Executor}），所以直接 {@code execute} 即可。
     */
    @Override
    public void runOnMainThread(Runnable task) {
        server.execute(task);
    }

    @Override
    public void dispatchConsoleCommand(String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /**
     * 每次调用都重新读盘。
     * <p>
     * 内核只在启动和 {@code /ac reload} 时取配置，所以这点开销无所谓；
     * 换成缓存就必须再设计一套失效通知，不值当。
     */
    @Override
    public ConfigSource config() {
        try {
            return FileConfigSource.load(configFile, defaultConfig.get());
        } catch (IOException e) {
            logger.warn("读取 " + configFile + " 失败，改用默认值: " + e.getMessage());
            return fallbackConfig;
        }
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    @Override
    public InputStream defaultConfigResource() {
        return defaultConfig.get();
    }

    @Override
    public BlockProbe blocks(WorldKey world) {
        return new McBlockProbe((ServerLevel) world.identity());
    }
}
