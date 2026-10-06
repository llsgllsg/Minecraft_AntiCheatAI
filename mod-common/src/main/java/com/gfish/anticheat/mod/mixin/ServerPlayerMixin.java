package com.gfish.anticheat.mod.mixin;

import com.gfish.anticheat.mod.ModRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 传送注入，对应 Bukkit 的 {@code PlayerTeleportEvent}。
 * <p>
 * <b>HEAD 开豁免、RETURN 重置基线</b>，两个时机刻意不同：
 * <ul>
 *   <li>HEAD：Paper 的 {@code PlayerTeleportEvent} 是<b>变更前</b>触发的，
 *       所以豁免也从变更前开始算，两端时间窗才对得上。</li>
 *   <li>RETURN：CraftBukkit 的 {@code resetPosition()} 是在传送<b>生效后</b>才把
 *       移动基线挪到新位置。要是在 HEAD 就挪，基线记录的还是旧坐标，
 *       下一个包会相对旧坐标算出一个巨大的位移。</li>
 * </ul>
 * 两个 {@code teleportTo} 重载都要接 —— 命令 {@code /tp} 走短的那个，
 * 跨维度与带朝向的传送走长的那个。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"))
    private void deepguard$onTeleportHead(double x, double y, double z, CallbackInfo ci) {
        dispatchTeleportStart();
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("RETURN"))
    private void deepguard$onTeleportReturn(double x, double y, double z, CallbackInfo ci) {
        dispatchTeleportEnd();
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
            at = @At("HEAD"))
    private void deepguard$onTeleportFullHead(ServerLevel level, double x, double y, double z,
                                              Set<Relative> relatives, float yRot, float xRot,
                                              boolean setCamera,
                                              CallbackInfoReturnable<Boolean> cir) {
        dispatchTeleportStart();
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
            at = @At("RETURN"))
    private void deepguard$onTeleportFullReturn(ServerLevel level, double x, double y, double z,
                                                Set<Relative> relatives, float yRot, float xRot,
                                                boolean setCamera,
                                                CallbackInfoReturnable<Boolean> cir) {
        dispatchTeleportEnd();
    }

    private void dispatchTeleportStart() {
        ModRuntime runtime = ModRuntime.get();
        if (runtime != null) {
            runtime.onTeleportStart((ServerPlayer) (Object) this);
        }
    }

    private void dispatchTeleportEnd() {
        ModRuntime runtime = ModRuntime.get();
        if (runtime != null) {
            runtime.onTeleportEnd((ServerPlayer) (Object) this);
        }
    }
}
