package com.gfish.anticheat;

public class BehaviorImageBuilder {
    private static final int CHANNELS = 12;
    private static final int TIME_STEPS = 128;

    public static float[][][] buildImage(BehaviorRecorder.BehaviorTick[] ticks) {
        float[][][] image = new float[CHANNELS][TIME_STEPS][1];
        int len = Math.min(ticks.length, TIME_STEPS);
        int offset = TIME_STEPS - len;

        for (int i = 0; i < len; i++) {
            int idx = offset + i;
            BehaviorRecorder.BehaviorTick t = ticks[ticks.length - len + i];
            float pitch = t.pitch;
            float yaw = t.yaw;
            float moveSpeed = (float) t.moveSpeed;
            float vertSpeed = (float) t.vertSpeed;
            float placing = t.placing ? 1.0f : 0.0f;
            float sprinting = t.sprinting ? 1.0f : 0.0f;
            float jumping = t.jumping ? 1.0f : 0.0f;

            float pitchChange = 0.0f;
            if (i > 0) {
                pitchChange = Math.abs(pitch - ticks[ticks.length - len + i - 1].pitch);
            }

            image[0][idx][0] = (pitch + 90.0f) / 180.0f;
            image[1][idx][0] = (yaw + 180.0f) / 360.0f;
            image[2][idx][0] = Math.min(moveSpeed / 10.0f, 1.0f);
            image[3][idx][0] = (vertSpeed + 1.0f) / 2.0f;
            image[4][idx][0] = placing;
            image[5][idx][0] = sprinting;
            image[6][idx][0] = jumping;
            image[7][idx][0] = Math.min(pitchChange / 90.0f, 1.0f);
            image[8][idx][0] = (pitchChange / 0.05f > 500) ? 1.0f : 0.0f;
            image[9][idx][0] = (sprinting == 1.0f && placing == 1.0f) ? 1.0f : 0.0f;
            int placeCount = 0;
            for (int j = Math.max(0, i - 19); j <= i; j++) {
                if (ticks[ticks.length - len + j].placing) placeCount++;
            }
            image[10][idx][0] = Math.min(placeCount / 10.0f, 1.0f);
            image[11][idx][0] = 0.0f;
        }
        return image;
    }
}