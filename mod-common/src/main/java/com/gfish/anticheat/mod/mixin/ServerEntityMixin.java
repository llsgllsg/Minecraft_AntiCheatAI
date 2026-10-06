package com.gfish.anticheat.mod.mixin;

import com.gfish.anticheat.mod.ModRuntime;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 击退豁免的主路径，对应 Bukkit 的 {@code PlayerVelocityEvent}。
 * <p>
 * <b>判据是 {@code entity.hurtMarked}，不是"速度变了"。</b>
 * CraftBukkit 在这里的谓词就是 {@code hurtMarked}，然后才把
 * {@code ClientboundSetEntityMotionPacket} 发给周围玩家。
 * <p>
 * 为什么不能用 {@code Entity#setDeltaMovement} 一网打尽：重力、摩擦、玩家自走
 * 每 tick 都在调它，那样所有人会永久处于豁免，检测全废。
 * 为什么不能只 hook {@code LivingEntity#knockback}：hurt 路径远不止 knockback
 * —— 爆炸、三叉戟激流、鞘翅加速、插件 setVelocity 都走 hurtMarked。
 * 漏掉它们，模组端这些情况下不会进入 1000ms 豁免，速度会被当成真实速度
 * → 误报。
 * <p>
 * 这是 entity tracker 的主路径；玩家攻击玩家那条路见 {@code PlayerAttackMixin}。
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void deepguard$onSendChanges(CallbackInfo ci) {
        // hurtMarked 在这个注入点还没被清掉，谓词与 CraftBukkit 一致
        if (!entity.hurtMarked) {
            return;
        }
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        ModRuntime runtime = ModRuntime.get();
        if (runtime != null) {
            runtime.onVelocity(player);
        }
    }
}
