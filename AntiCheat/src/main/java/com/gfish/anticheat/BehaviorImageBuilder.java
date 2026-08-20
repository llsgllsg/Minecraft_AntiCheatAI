package com.gfish.anticheat;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为特征构建器 —— 在线检测的特征编码。
 * <p>
 * <b>必须与 python/features.py 保持逐位一致</b>，否则训练 / 离线预测 / 在线检测
 * 三者会产生系统性偏差。修改任何通道时请同步更新两端。
 * <p>
 * 12 通道定义（对齐 python/features.py 的文档注释）：
 * <pre>
 *   0 pitch 归一化       7 |Δpitch| 归一化
 *   1 yaw  归一化        8 快速转头(>25°)二值
 *   2 水平速度           9 冲刺+放置 二值
 *   3 垂直速度           10 近20tick放置数
 *   4 placing 二值       11 放置节奏规律性(间隔方差)
 *   5 sprinting 二值
 *   6 jumping 二值
 * </pre>
 */
public final class BehaviorImageBuilder {

    private static final int CHANNELS = 12;
    private static final int TIME_STEPS = 128;

    /** 放置统计 / 间隔统计的滑动窗口（tick 数），与 python 端 PLACE_WINDOW 一致。 */
    private static final int PLACE_WINDOW = 20;
    /** 间隔方差除数，与 python 端 INTERVAL_VARIANCE_DIVISOR 一致。 */
    private static final double INTERVAL_VARIANCE_DIVISOR = 1000.0;
    /** 计算节奏规律所需的最小间隔数（6 次放置），与 python 端一致。 */
    private static final int MIN_INTERVALS = 5;

    private BehaviorImageBuilder() {
    }

    public static float[][][] buildImage(BehaviorRecorder.BehaviorTick[] ticks) {
        float[][][] image = new float[CHANNELS][TIME_STEPS][1];
        int n = ticks.length;
        if (n == 0) {
            return image;
        }

        int len = Math.min(n, TIME_STEPS);
        int offset = TIME_STEPS - len;

        for (int i = 0; i < len; i++) {
            int idx = offset + i;
            BehaviorRecorder.BehaviorTick t = ticks[n - len + i];
            float pitch = t.pitch;
            float yaw = t.yaw;
            float moveSpeed = (float) t.moveSpeed;
            float vertSpeed = (float) t.vertSpeed;
            boolean placing = t.placing;
            boolean sprinting = t.sprinting;
            boolean jumping = t.jumping;

            float pitchChange = 0.0f;
            if (i > 0) {
                pitchChange = Math.abs(pitch - ticks[n - len + i - 1].pitch);
            }

            image[0][idx][0] = (pitch + 90.0f) / 180.0f;
            image[1][idx][0] = (yaw + 180.0f) / 360.0f;
            image[2][idx][0] = Math.min(moveSpeed / 10.0f, 1.0f);
            image[3][idx][0] = (vertSpeed + 1.0f) / 2.0f;
            image[4][idx][0] = placing ? 1.0f : 0.0f;
            image[5][idx][0] = sprinting ? 1.0f : 0.0f;
            image[6][idx][0] = jumping ? 1.0f : 0.0f;
            image[7][idx][0] = Math.min(pitchChange / 90.0f, 1.0f);
            image[8][idx][0] = (pitchChange / 0.05f > 500) ? 1.0f : 0.0f;
            image[9][idx][0] = (sprinting && placing) ? 1.0f : 0.0f;

            int placeCount = 0;
            for (int j = Math.max(0, i - (PLACE_WINDOW - 1)); j <= i; j++) {
                if (ticks[n - len + j].placing) placeCount++;
            }
            image[10][idx][0] = Math.min(placeCount / 10.0f, 1.0f);

            image[11][idx][0] = placingRegularity(ticks, n - len, i);
        }
        return image;
    }

    /**
     * 通道 11：放置节奏规律性。稳定节奏（外挂）趋近 1，随机间隔（真人）趋近 0。
     * 必须与 python/features.py#_placing_regularity 一致。
     */
    private static float placingRegularity(BehaviorRecorder.BehaviorTick[] ticks, int base, int i) {
        if (i < 5) {
            return 0.0f;
        }

        List<Long> intervals = new ArrayList<>();
        long lastTime = -1;
        int start = Math.max(0, base + i - (PLACE_WINDOW - 1));
        for (int j = start; j <= base + i; j++) {
            BehaviorRecorder.BehaviorTick pt = ticks[j];
            if (!pt.placing) {
                continue;
            }
            if (lastTime != -1) {
                intervals.add(pt.timestamp - lastTime);
            }
            lastTime = pt.timestamp;
        }

        if (intervals.size() < MIN_INTERVALS) {
            return 0.0f;
        }

        // 总体方差，与 numpy np.var 一致
        double mean = 0;
        for (long v : intervals) {
            mean += v;
        }
        mean /= intervals.size();

        double variance = 0;
        for (long v : intervals) {
            double d = v - mean;
            variance += d * d;
        }
        variance /= intervals.size();

        return Math.max(0.0f, 1.0f - (float) (variance / INTERVAL_VARIANCE_DIVISOR));
    }
}
