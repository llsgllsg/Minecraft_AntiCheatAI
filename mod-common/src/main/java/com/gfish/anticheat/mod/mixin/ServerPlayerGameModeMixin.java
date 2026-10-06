package com.gfish.anticheat.mod.mixin;

import com.gfish.anticheat.mod.ModRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块放置注入，对应 Bukkit 的 {@code BlockPlaceEvent}。
 * <p>
 * 用 {@code useItemOn} 而不是某个"放置事件"，是为了和 Bukkit 保持同频：
 * {@code consumesAction()} 为真才算真正消耗了这次交互（真放了方块），
 * 空挥、右键门、打火石失败等都返回 PASS，不该记成一次放置。
 * <p>
 * 这也正是<b>不用</b> NeoForge 原生 {@code EntityPlaceEvent} 的原因 —— 它的
 * 触发条件与 {@code useItemOn} + {@code consumesAction()} 并不等价，
 * 两端的 {@code placing} 特征会系统性错开。两个模组共用这一份 Mixin，
 * 行为由构造保证一致，而不是靠逐条对齐事件语义。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void deepguard$afterUseItemOn(ServerPlayer player, Level level, ItemStack stack,
                                          InteractionHand hand, BlockHitResult hitResult,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) {
            return;
        }
        ModRuntime runtime = ModRuntime.get();
        if (runtime != null) {
            runtime.onBlockPlace(player);
        }
    }
}
