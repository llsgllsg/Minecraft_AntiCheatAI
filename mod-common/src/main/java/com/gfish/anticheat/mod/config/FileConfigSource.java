package com.gfish.anticheat.mod.config;

import com.gfish.anticheat.core.platform.ConfigSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把 {@code config.yml} 读成内核要的 {@link ConfigSource}。
 * <p>
 * 配置文件缺失时，先用平台提供的内置资源写一份出来 —— 与 Paper 的
 * {@code saveDefaultConfig()} 行为对齐，保证三端"开箱即用"的默认值一致。
 */
public final class FileConfigSource implements ConfigSource {

    private final SimpleYaml yaml;

    private FileConfigSource(SimpleYaml yaml) {
        this.yaml = yaml;
    }

    /**
     * 从磁盘读配置；文件不存在时用 {@code defaultResource} 写一份再读。
     *
     * @param defaultResource 内置默认配置，可为 null（那就只能读空配置）
     */
    public static FileConfigSource load(Path file, InputStream defaultResource) throws IOException {
        if (!Files.exists(file) && defaultResource != null) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = defaultResource) {
                Files.copy(in, file);
            }
        }
        String text = Files.exists(file)
                ? Files.readString(file, StandardCharsets.UTF_8)
                : "";
        return new FileConfigSource(SimpleYaml.parse(text));
    }

    /** 全空的配置源：所有查询都落回默认值。配置文件读不出来时用它兜底。 */
    public static ConfigSource empty() {
        return new FileConfigSource(SimpleYaml.parse(""));
    }

    @Override
    public boolean getBoolean(String key, boolean def) {
        return yaml.getBoolean(key, def);
    }

    @Override
    public double getDouble(String key, double def) {
        return yaml.getDouble(key, def);
    }

    @Override
    public int getInt(String key, int def) {
        return yaml.getInt(key, def);
    }

    @Override
    public String getString(String key, String def) {
        return yaml.getString(key, def);
    }
}
