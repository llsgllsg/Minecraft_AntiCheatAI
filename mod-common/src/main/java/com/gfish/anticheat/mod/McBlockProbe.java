package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.platform.BlockProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** {@link BlockProbe} 的 Mojang 侧实现。 */
public final class McBlockProbe implements BlockProbe {

    private final ServerLevel level;

    public McBlockProbe(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return state(x, y, z).isAir();
    }

    /**
     * 实心判定。
     * <p>
     * {@code BlockStateBase#isSolid()} 在 26.2 标了 {@code @Deprecated}，但这里
     * 仍然用它：Paper 侧的 {@code Material#isSolid()} 语义就是它，换成
     * {@code blocksMotion()} 反而可能让三端的"脚下算不算地面"出现分歧。
     * 真要换的话必须先在 Paper 测试服上对比两者对台阶 / 半砖 / 流体的取值。
     */
    @Override
    @SuppressWarnings("deprecation")
    public boolean isSolid(int x, int y, int z) {
        return state(x, y, z).isSolid();
    }

    private BlockState state(int x, int y, int z) {
        // 越界（y 低于 minY 或高于 maxY）时 getBlockState 返回虚空空气，
        // 与 Bukkit 在世界外 getBlock() 得到空气的行为一致，不必额外判界。
        return level.getBlockState(new BlockPos(x, y, z));
    }
}
