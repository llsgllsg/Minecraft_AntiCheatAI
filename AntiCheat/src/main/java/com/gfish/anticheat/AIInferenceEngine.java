package com.gfish.anticheat;

import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.Collections;

public class AIInferenceEngine {
    private OrtEnvironment env;
    private OrtSession session;
    private boolean loaded = false;

    public boolean loadModel(String modelPath) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = env.createSession(modelPath, opts);
            loaded = true;
            return true;
        } catch (OrtException e) {
            e.printStackTrace();
            return false;
        }
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