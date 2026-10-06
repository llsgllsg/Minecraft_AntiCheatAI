package com.gfish.anticheat.core.spi;

/**
 * AI 推理 SPI —— 内核只认这个接口，具体实现（直连 onnxruntime、还是经子类加载器
 * 加载的桥接实现）由各平台决定。
 * <p>
 * 放在独立的 {@code spi} 包里，是为了让实现在<b>另一个类加载器</b>里也能被认出来：
 * 模组端的 onnxruntime 是运行时下载后用 {@code URLClassLoader} 加载的，实现类
 * 必须能被内核的父加载器看见，否则会 {@code NoClassDefFoundError}。
 */
public interface AiEngine {

    /** 是否已成功加载模型。未加载时 {@link #infer} 应返回"正常"兜底。 */
    boolean isLoaded();

    /**
     * 模型期望的通道数（从输入张量形状读出）；读不到时为 -1。
     * <p>
     * 调用方用它比对特征通道数：模型通道数 &lt; 特征通道数时只取前 N 个通道
     * （新增通道一律追加在末尾，所以取前 N 等价于旧版编码）；模型通道数更多
     * 则说明特征不够用，必须停用 AI。
     */
    int modelChannels();

    /**
     * 推理。输入是 {@code [通道][时间][1]} 的特征图。
     *
     * @return 各类别概率；下标 1 是作弊概率
     */
    float[] infer(float[][][] input);
}
