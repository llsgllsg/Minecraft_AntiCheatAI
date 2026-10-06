package com.gfish.anticheat.core.platform;

/**
 * 方块查询。坐标是已经向下取整的方块坐标（见 {@link Pos#toBlockCoord}）。
 * <p>
 * FlyCheck 需要「不是空气 **且** 是实心」这个复合判断，所以两个谓词都要暴露 ——
 * 只给一个 isSolid 会让「流体/空气」和「非实心方块（草、告示牌）」无法区分。
 */
public interface BlockProbe {

    boolean isAir(int x, int y, int z);

    boolean isSolid(int x, int y, int z);
}
