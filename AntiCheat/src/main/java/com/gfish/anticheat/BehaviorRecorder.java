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
    }
}