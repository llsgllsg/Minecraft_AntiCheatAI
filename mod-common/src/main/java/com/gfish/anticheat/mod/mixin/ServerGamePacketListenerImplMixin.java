package com.gfish.anticheat.mod.mixin;

import com.gfish.anticheat.mod.ModRuntime;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移动包注入。<b>两个入口都要接</b>。
 * <p>
 * ⚠️ 漏掉 {@code handleMoveVehicle} 是本项目最容易犯、也最难发现的错误：
 * CraftBukkit 在 {@code handleMoveVehicle} 里同样会触发 {@code PlayerMoveEvent}
 * （补丁注释原话 "CraftBukkit start - fire PlayerMoveEvent"），所以骑船玩家的
 * 移动必须也进内核，否则船速检测在模组端<b>完全失效</b> —— 而且单机冒烟测试
 * 不会暴露（自己不开船就看不出来）。
 * <p>
 * 注入点在 HEAD：此时本包还没被应用，{@code player.getX()} 仍是上一 tick 的位置，
 * 正好充当"包里没带该分量"时的兜底值，与 CraftBukkit 构造 {@code to} 的方式一致。
 * 是否真正派发给内核由 {@code MoveEventEmitter} 的死区规则决定。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    /** vanilla 里这就是个 public 字段，直接影子引用。 */
    @Shadow
    public ServerPlayer player;

    /**
     * 玩家自身的移动包。
     * <p>
     * {@code getX(player.getX())} 这种带兜底的取值，恰好等价于 CraftBukkit 的
     * "按 hasPosition 决定是否覆盖" —— 包没带位置就返回兜底值，等于没覆盖。
     */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void deepguard$onMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ModRuntime runtime = ModRuntime.get();
        if (runtime == null) {
            return;
        }
        runtime.onMovePacket(player,
                packet.getX(player.getX()),
                packet.getY(player.getY()),
                packet.getZ(player.getZ()),
                packet.getYRot(player.getYRot()),
                packet.getXRot(player.getXRot()),
                packet.isOnGround());
    }

    /**
     * 骑乘（船 / 矿车 / 猪等）的移动包。
     * <p>
     * 这个包的坐标是<b>载具</b>的位置，对船速检测正是需要的值。
     */
    @Inject(method = "handleMoveVehicle", at = @At("HEAD"))
    private void deepguard$onMoveVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        ModRuntime runtime = ModRuntime.get();
        if (runtime == null) {
            return;
        }
        Vec3 pos = packet.position();
        runtime.onMovePacket(player, pos.x, pos.y, pos.z,
                packet.yRot(), packet.xRot(), packet.onGround());
    }
}
