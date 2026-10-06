package com.gfish.anticheat.mod.mixin;

import com.gfish.anticheat.mod.ModRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 击退豁免的第二条路径：玩家攻击玩家。
 * <p>
 * CraftBukkit 在 {@code Player#attack} 里也放了一次 {@code PlayerVelocityEvent}，
 * 谓词同样是<b>被打目标</b>的 {@code hurtMarked} —— 而且豁免要开在<b>被打的人</b>身上，
 * 不是打人的那个。{@code ServerEntity#sendChanges} 那条路要等 entity tracker 下个
 * tick 才跑到，这条则是在命中当刻立即生效，两者缺一不可。
 */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void deepguard$onAttack(Entity target, CallbackInfo ci) {
        // 只关心服务端玩家的数据；客户端/单人端的 Player 实例不是 ServerPlayer
        if (!((Object) this instanceof ServerPlayer)) {
            return;
        }
        if (!target.hurtMarked) {
            return;
        }
        if (!(target instanceof ServerPlayer victim)) {
            return;
        }
        ModRuntime runtime = ModRuntime.get();
        if (runtime != null) {
            runtime.onVelocity(victim);
        }
    }
}
