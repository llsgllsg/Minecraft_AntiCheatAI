package com.gfish.anticheat;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为特征构建器 —— 在线检测的特征编码。
 * <p>
 * <b>必须与 python/features.py 保持逐位一致</b>，否则训练 / 离线预测 / 在线检测
 * 三者会产生系统性偏差。修改任何通道时请同步更新两端。
 * <p>
 * 17 通道定义（对齐 python/features.py 的文档注释）：
 * <pre>
 *   0 pitch 归一化        9 冲刺+放置 二值
 *   1 yaw  归一化        10 近20tick放置数
 *   2 水平速度           11 放置节奏规律性(间隔方差)
 *   3 垂直速度           12 是否在载具 二值
 *   4 placing 二值       13 是否鞘翅滑翔 二值
 *   5 sprinting 二值     14 Δx 每tick水平位移(带符号)
 *   6 jumping 二值       15 Δy 每tick垂直位移(带符号)
 *   7 |Δpitch| 归一化    16 Δz 每tick水平位移(带符号)
 *   8 快速转头(>25°)二值
 * </pre>
 *
 * 通道 12/13 依赖录制时的 inVehicle / gliding 字段 —— 加字段之前录的旧数据恒为 false。
 * 通道 14/15/16 由相邻两 tick 的坐标差算出，不依赖新字段，旧数据同样有效。
 * 载具的具体类型（vehicleType）只记录进 jsonl 供人工分析，不参与特征编码。
 */
public final class BehaviorImageBuilder {

    /** 特征通道数。模型输入必须与之匹配，否则加载时会被拒绝。 */
    public static final int CHANNELS = 17;
    private static final int TIME_STEPS = 128;

    /** 放置统计 / 间隔统计的滑动窗口（tick 数），与 python 端 PLACE_WINDOW 一致。 */
    private static final int PLACE_WINDOW = 20;
    /** 间隔方差除数，与 python 端 INTERVAL_VARIANCE_DIVISOR 一致。 */
    private static final double INTERVAL_VARIANCE_DIVISOR = 1000.0;
    /** 计算节奏规律所需的最小间隔数（6 次放置），与 python 端一致。 */
    private static final int MIN_INTERVALS = 5;

    /** 每 tick 位移的归一化尺度（格/tick），与 python 端 DELTA_H_SCALE / DELTA_V_SCALE 一致。 */
    private static final double DELTA_H_SCALE = 1.0;
    private static final double DELTA_V_SCALE = 4.0;

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
            boolean inVehicle = t.inVehicle;
            boolean gliding = t.gliding;

            float pitchChange = 0.0f;
            // 与上一 tick 的相对位移：由坐标差算出，不依赖录制侧新增字段
            double deltaX = 0.0;
            double deltaY = 0.0;
            double deltaZ = 0.0;
            if (i > 0) {
                BehaviorRecorder.BehaviorTick prev = ticks[n - len + i - 1];
                pitchChange = Math.abs(pitch - prev.pitch);
                deltaX = t.posX - prev.posX;
                deltaY = t.posY - prev.posY;
                deltaZ = t.posZ - prev.posZ;
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

            // 载具 / 鞘翅：区分「合法滞空」与「飞行外挂」的关键信号
            image[12][idx][0] = inVehicle ? 1.0f : 0.0f;
            image[13][idx][0] = gliding ? 1.0f : 0.0f;

            // 与上一 tick 的相对位移（带符号，0.5 表示无位移）
            image[14][idx][0] = signedUnit(deltaX, DELTA_H_SCALE);
            image[15][idx][0] = signedUnit(deltaY, DELTA_V_SCALE);
            image[16][idx][0] = signedUnit(deltaZ, DELTA_H_SCALE);
        }
        return image;
    }

    /** 把带符号的位移按 scale 归一化到 [0,1]（0.5 表示没有位移），与 python 端 _signed_unit 一致。 */
    private static float signedUnit(double delta, double scale) {
        double clamped = Math.max(-1.0, Math.min(1.0, delta / scale));
        return (float) ((clamped + 1.0) / 2.0);
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
