package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.platform.CommandActor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** {@link CommandActor} 的 Mojang 侧实现。 */
public final class McCommandActor implements CommandActor {

    private final CommandSourceStack source;
    private final MinecraftServer server;

    public McCommandActor(CommandSourceStack source, MinecraftServer server) {
        this.source = source;
        this.server = server;
    }

    @Override
    public String name() {
        return source.getTextName();
    }

    /**
     * 控制台（以及命令方块等非玩家来源）一律放行 —— 与 Bukkit 侧
     * {@code CommandSender#hasPermission} 对控制台恒真的行为一致。
     */
    @Override
    public boolean isAdmin() {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return new McPlayerHandle(player).hasPermission("deepguard.admin");
    }

    @Override
    public void sendMessage(String message) {
        source.sendSystemMessage(Component.literal(message));
    }

    @Override
    public List<String> onlinePlayerNames() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        List<String> names = new ArrayList<>(players.size());
        for (ServerPlayer p : players) {
            // 与内核一致，用游戏档案名而不是显示名
            names.add(p.getGameProfile().name());
        }
        return names;
    }
}
