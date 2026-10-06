package com.gfish.anticheat.mod.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把 {@code Player#isImmobile()}（protected）暴露出来。
 * <p>
 * CraftBukkit 的移动事件发射逻辑里有一句 {@code !player.isImmobile()}，
 * 必须原样复刻 —— 死亡 / 睡眠中的玩家不该产生移动事件。
 * 这个方法在 vanilla 里是 protected，只能靠 {@code @Invoker} 拿到。
 * <p>
 * 方法名带 {@code deepguard$} 前缀，避免与将来 vanilla 新增的同名方法撞车。
 */
@Mixin(Player.class)
public interface PlayerInvoker {

    @Invoker("isImmobile")
    boolean deepguard$isImmobile();
}
