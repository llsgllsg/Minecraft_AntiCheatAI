package com.gfish.anticheat;

import java.util.ArrayList;
import java.util.List;

public class BehaviorRecorder {
    private final List<BehaviorTick> ticks = new ArrayList<>();
    private static final int MAX_TICKS = 600;

    public void record(BehaviorTick tick) {
        ticks.add(tick);
        if (ticks.size() > MAX_TICKS) {
            ticks.remove(0);
        }
    }

    public BehaviorTick[] getRecentTicks(int n) {
        if (ticks.isEmpty()) return new BehaviorTick[0];
        int from = Math.max(0, ticks.size() - n);
        return ticks.subList(from, ticks.size()).toArray(new BehaviorTick[0]);
    }

    public static class BehaviorTick {
        public long timestamp;
        public float pitch;
        public float yaw;
        public double posX, posY, posZ;
        public boolean placing;
        public boolean sprinting;
        public boolean jumping;
        public boolean onGround;
        public double moveSpeed;
        public double vertSpeed;

        /** 是否骑乘中（船 / 矿车 / 动物等）。对应特征通道 12。 */
        public boolean inVehicle;
        /** 是否正在用鞘翅滑翔。对应特征通道 13。 */
        public boolean gliding;
        /** 载具的具体类型（如 minecraft:boat）；不在载具时为空串。只记录，不参与特征编码。 */
        public String vehicleType;
    }
}