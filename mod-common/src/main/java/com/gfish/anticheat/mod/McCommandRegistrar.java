package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.platform.CommandActor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * 把 {@code /anticheat}（别名 {@code /ac}）注册进 Brigadier。
 * <p>
 * 两个模组共用这一份：命令树与 {@code /ac <子命令>} 的参数形态完全一致，
 * 各端只需在"注册命令"事件里调一次 {@link #register}。
 * <p>
 * 内核那边只认 {@code String[]}，所以这里做一层翻译 —— 好处是命令树
 * （Brigadier 类型）不会渗进 core，Paper 模块的 Maven 编译也就拿不到
 * Mojang 类型的麻烦。
 */
public final class McCommandRegistrar {

    private final Supplier<ModRuntime> runtime;

    public McCommandRegistrar(Supplier<ModRuntime> runtime) {
        this.runtime = runtime;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(buildRoot("anticheat"));
        // /ac 别名：redirect 到同一节点，参数与提示都跟着走
        dispatcher.register(buildRoot("ac").redirect(node));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        return Commands.literal(name)
                .requires(McCommandRegistrar::isAdmin)
                .executes(ctx -> run(ctx.getSource(), new String[0]))
                .then(Commands.literal("reload")
                        .executes(ctx -> run(ctx.getSource(), new String[]{"reload"})))
                .then(Commands.literal("update")
                        .executes(ctx -> run(ctx.getSource(), new String[]{"update"})))
                .then(Commands.literal("lookup")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> run(ctx.getSource(), new String[]{
                                        "lookup", StringArgumentType.getString(ctx, "code")}))))
                .then(Commands.literal("report")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (var p : ctx.getSource().getServer()
                                            .getPlayerList().getPlayers()) {
                                        // 与内核一致，补全用游戏档案名
                                        builder.suggest(p.getGameProfile().name());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> run(ctx.getSource(), new String[]{
                                        "report", StringArgumentType.getString(ctx, "player")}))));
    }

    /** 控制台一律放行；玩家需要 deepguard.admin（模组端回退到原版 2 级权限）。 */
    private static boolean isAdmin(CommandSourceStack source) {
        if (source.getPlayer() == null) {
            return true;
        }
        return new McPlayerHandle(source.getPlayer()).hasPermission("deepguard.admin");
    }

    private int run(CommandSourceStack source, String[] args) {
        ModRuntime current = runtime.get();
        if (current == null) {
            source.sendSystemMessage(Component.literal("§cDeepGuard 尚未就绪。"));
            return 0;
        }
        CommandActor actor = new McCommandActor(source, current.platform().server());
        current.core().commands().execute(actor, args);
        return 1;
    }
}
