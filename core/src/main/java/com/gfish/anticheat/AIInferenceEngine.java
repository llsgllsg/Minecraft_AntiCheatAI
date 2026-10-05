package com.gfish.anticheat;

import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.Collections;

public class AIInferenceEngine {
    private OrtEnvironment env;
    private OrtSession session;
    private boolean loaded = false;
    /** 模型期望的通道数（从输入张量形状读出）；读不到时为 -1。 */
    private int modelChannels = -1;

    public boolean loadModel(String modelPath) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = env.createSession(modelPath, opts);
            modelChannels = readChannelCount(session);
            loaded = true;
            return true;
        } catch (OrtException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 模型期望的通道数（形状为 [batch, channels, time] 时取中间那维）。
     * 调用方用它比对 {@link BehaviorImageBuilder#CHANNELS}：模型是自动下载的，
     * 代码与模型版本错配时必须提前发现，否则会在每次推理时才抛异常。
     */
    public int getModelChannels() {
        return modelChannels;
    }

    private static int readChannelCount(OrtSession session) {
        try {
            for (NodeInfo node : session.getInputInfo().values()) {
                if (node.getInfo() instanceof TensorInfo info) {
                    long[] shape = info.getShape();
                    if (shape.length == 3 && shape[1] > 0) {
                        return (int) shape[1];
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不到就当未知，交由调用方按 -1 处理
        }
        return -1;
    }

    public float[] infer(float[][][] input) {
        if (!loaded) return new float[]{1.0f, 0.0f};
        try {
            int c = input.length;
            int t = input[0].length;
            float[] flat = new float[c * t];
            for (int i = 0; i < c; i++) {
                for (int j = 0; j < t; j++) {
                    flat[i * t + j] = input[i][j][0];
                }
            }
            OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{1, c, t});
            OrtSession.Result result = session.run(Collections.singletonMap("behavior_sequence", tensor));
            float[][] output = (float[][]) result.get(0).getValue();
            return output[0];
        } catch (OrtException e) {
            e.printStackTrace();
            return new float[]{1.0f, 0.0f};
        }
    }
}