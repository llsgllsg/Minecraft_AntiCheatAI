package com.gfish.anticheat.core.platform;

/**
 * 配置读取 SPI。
 * <p>
 * 内核只持有类型化的 {@code DeepGuardConfig}，YAML 的解析留在各平台：
 * Paper 继续走 {@code JavaPlugin#getConfig()}（完全不改它现有的加载路径），
 * 模组走 SnakeYAML。这样 Paper 的配置行为一个字都没变。
 */
public interface ConfigSource {

    boolean getBoolean(String key, boolean def);

    double getDouble(String key, double def);

    int getInt(String key, int def);

    String getString(String key, String def);
}
