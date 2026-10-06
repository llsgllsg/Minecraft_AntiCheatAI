package com.gfish.anticheat.core.config;

import com.gfish.anticheat.core.platform.ConfigSource;

/**
 * 类型化配置。内核只认这个对象，YAML 的解析在平台侧。
 * <p>
 * 每个字段的默认值都<b>逐字照抄</b> Paper 版 {@code AntiCheatPlugin#loadConfigValues}：
 * 这些默认值只在配置项缺失时生效，改掉会让"同一份 config.yml 在三端表现不同"。
 * 注意 config.yml 里 {@code speed.enabled: true} / {@code max-speed: 150.0}
 * 才是线上实际生效的值，这里的 false / 5.0 是缺 key 时的兜底。
 */
public final class DeepGuardConfig {

    /** UpdateManager 里写死的默认地址，配置留空时使用。 */
    public static final String DEFAULT_VERSION_URL =
            "https://api.github.com/repos/llsgllsg/Minecraft_AntiCheatAI/releases/latest";
    public static final String DEFAULT_MODEL_URL =
            "https://github.com/llsgllsg/Minecraft_AntiCheatAI/releases/latest/download/scaffold_detector.onnx";

    public static final String DEFAULT_PUNISH_COMMAND =
            "kick %player% §c[DeepGuard] 检测到异常行为 [封禁码: %code%]";
    public static final String DEFAULT_BAN_COMMAND =
            "ban %player% §c[DeepGuard] 多次作弊行为 [封禁码: %code%]";
    public static final String DEFAULT_SPEED_COMMAND =
            "kick %player% §c你移动速度过快！";

    public final String punishCommand;
    public final String banCommand;
    public final boolean geyserBypassEnabled;

    public final boolean flyCheck;
    public final boolean boatSpeedCheck;
    public final double maxBoatSpeed;
    public final boolean speedCheckEnabled;
    public final double maxSpeed;
    public final String apPlaceholder;
    public final String speedPunishCommand;

    public final boolean aiEnabled;
    public final String modelPath;
    public final double autoPunishThreshold;
    public final double alertThreshold;
    public final int analysisSeconds;

    public final boolean updatesEnabled;
    public final boolean versionCheckEnabled;
    public final boolean autoDownloadModelEnabled;
    public final String versionUrl;
    public final String modelUrl;

    private DeepGuardConfig(ConfigSource c) {
        this.punishCommand = c.getString("punish-command", DEFAULT_PUNISH_COMMAND);
        this.banCommand = c.getString("ban-command", DEFAULT_BAN_COMMAND);
        this.geyserBypassEnabled = c.getBoolean("geyser-bypass.enabled", true);

        this.flyCheck = c.getBoolean("movement.fly", true);
        this.boatSpeedCheck = c.getBoolean("movement.boat-speed", true);
        this.maxBoatSpeed = c.getDouble("movement.max-boat-speed", 12.0);
        this.speedCheckEnabled = c.getBoolean("movement.speed.enabled", false);
        this.maxSpeed = c.getDouble("movement.speed.max-speed", 5.0);
        this.apPlaceholder = c.getString("movement.speed.ap-placeholder", "%ap_moving:max%");
        this.speedPunishCommand = c.getString("movement.speed.command", DEFAULT_SPEED_COMMAND);

        this.aiEnabled = c.getBoolean("ai.enabled", true);
        this.modelPath = c.getString("ai.model-path", "scaffold_detector.onnx");
        this.autoPunishThreshold = c.getDouble("ai.auto-punish-threshold", 0.85);
        this.alertThreshold = c.getDouble("ai.alert-threshold", 0.5);
        this.analysisSeconds = c.getInt("ai.analysis-seconds", 30);

        this.updatesEnabled = c.getBoolean("updates.enabled", true);
        this.versionCheckEnabled = c.getBoolean("updates.version-check", true);
        this.autoDownloadModelEnabled = c.getBoolean("updates.auto-download-model", true);
        this.versionUrl = orDefault(c.getString("updates.version-url", ""), DEFAULT_VERSION_URL);
        this.modelUrl = orDefault(c.getString("updates.model-url", ""), DEFAULT_MODEL_URL);
    }

    public static DeepGuardConfig load(ConfigSource source) {
        return new DeepGuardConfig(source);
    }

    /** 空串/null/全空白都当作"没配"，回退到默认地址。 */
    private static String orDefault(String value, String def) {
        return (value == null || value.isBlank()) ? def : value;
    }
}
