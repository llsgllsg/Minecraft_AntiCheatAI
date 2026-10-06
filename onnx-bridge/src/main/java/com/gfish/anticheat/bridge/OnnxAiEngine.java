package com.gfish.anticheat.bridge;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.gfish.anticheat.core.spi.AiEngine;

import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * 直连 onnxruntime 的 {@link AiEngine} 实现。
 * <p>
 * ⚠️ <b>这个类绝不能被模组的主类加载器看到。</b>它被编译成一个独立的
 * {@code onnx-bridge.jar} 打进模组资源，运行时由子 {@code URLClassLoader} 加载
 * ——原因见 {@code OnnxRuntimeLoader}：父加载器一旦先定义了这个类，它引用的
 * {@code ai.onnxruntime} 也会按父加载器去解析，而 onnxruntime 只存在于子加载器里，
 * 结果是 {@code NoClassDefFoundError}。
 * <p>
 * 也正因为如此，本类只允许依赖 <b>JDK 类型</b>与 {@code core.spi} 里的接口：
 * 任何平台类型（Mojang / Bukkit / 加载器 API）在这里都拿不到。
 */
public final class OnnxAiEngine implements AiEngine {

    /** 与训练导出时的 input_names 一致（见 python/train_model.py）。 */
    private static final String INPUT_NAME = "behavior_sequence";

    private OrtEnvironment env;
    private OrtSession session;
    private int modelChannels = -1;

    /**
     * @param modelPath ONNX 模型文件路径
     * @throws IllegalStateException 加载失败时抛出，消息里带上原因；
     *                               父类加载器只能拿到这个异常（它看不到 {@code OrtException}）
     */
    public OnnxAiEngine(String modelPath) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = env.createSession(modelPath, opts);
            modelChannels = readChannelCount(session);
        } catch (OrtException e) {
            close();
            throw new IllegalStateException("加载 ONNX 模型失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isLoaded() {
        return session != null;
    }

    @Override
    public int modelChannels() {
        return modelChannels;
    }

    @Override
    public float[] infer(float[][][] input) {
        if (session == null) {
            return new float[]{1.0f, 0.0f};
        }
        try {
            int c = input.length;
            // 向下兼容：模型通道数比特征通道数少时只取前 N 个通道。
            // 新增通道一律追加在末尾，所以取前 N 等价于旧版编码 ——
            // 升级后不必立刻换模型，旧模型继续可用。
            if (modelChannels > 0) {
                if (c < modelChannels) {
                    // 特征比模型还少，无法满足，按「正常」兜底（与加载失败一致）
                    return new float[]{1.0f, 0.0f};
                }
                c = modelChannels;
            }
            int t = input[0].length;
            float[] flat = new float[c * t];
            for (int i = 0; i < c; i++) {
                for (int j = 0; j < t; j++) {
                    flat[i * t + j] = input[i][j][0];
                }
            }
            OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{1, c, t});
            try (OrtSession.Result result =
                         session.run(Collections.singletonMap(INPUT_NAME, tensor))) {
                float[][] output = (float[][]) result.get(0).getValue();
                return output[0];
            } finally {
                tensor.close();
            }
        } catch (OrtException e) {
            return new float[]{1.0f, 0.0f};
        }
    }

    /**
     * 关闭会话，释放 native 侧资源。
     * <p>
     * 模型热更新时替换引擎后<b>必须</b>调用，否则旧会话会一直占着内存。
     * 不动 {@code env}：它是 JVM 级单例（{@code OrtEnvironment.getEnvironment()}），
     * 而且 native 库一旦加载就卸不掉。
     */
    public void close() {
        OrtSession current = session;
        session = null;
        if (current != null) {
            try {
                current.close();
            } catch (OrtException ignored) {
                // 关闭失败只能忽略：此时已无补救手段，抛出去反而会打断模型热更新
            }
        }
    }

    /**
     * 模型期望的通道数（形状为 [batch, channels, time] 时取中间那维）。
     * <p>
     * 调用方用它比对特征通道数：模型是自动下载的，代码与模型版本错配时
     * 必须提前发现，否则会在每次推理时才抛异常。
     */
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
}
