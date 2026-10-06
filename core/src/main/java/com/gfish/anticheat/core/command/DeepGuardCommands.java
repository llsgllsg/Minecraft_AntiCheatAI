package com.gfish.anticheat.core.command;

import com.gfish.anticheat.core.DeepGuardCore;
import com.gfish.anticheat.core.TrackedPlayer;
import com.gfish.anticheat.core.platform.CommandActor;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.spi.UpdateService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ac} 命令的实现。
 * <p>
 * 内核只认 {@code String[]} 形态的参数，不返回 Brigadier / Bukkit 的命令对象 ——
 * 那会把 {@code net.minecraft.commands.CommandSourceStack} 钉进内核，
 * 而 Paper 模块的 Maven 编译根本拿不到 Mojang 类型。
 * 三端各写约 30 行注册代码，把命令转到这里。
 */
public final class DeepGuardCommands {

    public static final List<String> SUBCOMMANDS = List.of("report", "lookup", "reload", "update");

    private final DeepGuardCore core;

    public DeepGuardCommands(DeepGuardCore core) {
        this.core = core;
    }

    /** @return 是否已处理（与 Bukkit {@code onCommand} 的约定一致） */
    public boolean execute(CommandActor actor, String[] args) {
        if (!actor.isAdmin()) {
            actor.sendMessage("§c权限不足。");
            return true;
        }
        if (args.length == 0) {
            actor.sendMessage("§e/ac report <玩家> §7- AI 分析玩家行为");
            actor.sendMessage("§e/ac lookup <封禁码> §7- 查看违规记录详情");
            actor.sendMessage("§e/ac update §7- 检查更新并同步最新模型");
            actor.sendMessage("§e/ac reload §7- 重载配置");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                core.reloadConfig();
                actor.sendMessage("§a配置已重载。");
                break;

            case "report": {
                if (args.length < 2) {
                    actor.sendMessage("§c请指定玩家名。");
                    return true;
                }
                PlayerHandle target = core.platform().playerByName(args[1]);
                if (target == null) {
                    actor.sendMessage("§c玩家不在线。");
                    return true;
                }
                handleReport(actor, target);
                break;
            }

            case "lookup":
                if (args.length < 2) {
                    actor.sendMessage("§c请提供封禁码。");
                    return true;
                }
                handleLookup(actor, args[1]);
                break;

            case "update": {
                UpdateService updates = core.updateService();
                if (updates == null) {
                    actor.sendMessage("§c当前平台不支持自动更新，请手动替换模型文件。");
                    return true;
                }
                actor.sendMessage("§6正在检查更新并同步最新模型...");
                updates.checkVersionAsync();
                updates.downloadModelAsync().thenRun(() ->
                        core.platform().runOnMainThread(() ->
                                actor.sendMessage("§a更新检查完成，模型已同步到最新。")));
                break;
            }

            default:
                actor.sendMessage("§c未知子命令。");
        }
        return true;
    }

    public List<String> tabComplete(CommandActor actor, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("report")) {
            return actor.onlinePlayerNames();
        }
        return List.of();
    }

    private void handleReport(CommandActor actor, PlayerHandle target) {
        if (!core.isAiReady()) {
            actor.sendMessage("§cAI 引擎未启用。");
            return;
        }
        TrackedPlayer tracked = core.trackedOrNull(target.uuid());
        if (tracked == null) {
            actor.sendMessage("§c该玩家尚无足够数据。");
            return;
        }

        actor.sendMessage("§6正在分析 " + target.name() + " 的最近 "
                + core.config().analysisSeconds + " 秒行为...");

        core.analyzeAsync(target, probs -> {
            float cheatProb = probs[1];
            actor.sendMessage("§6===== AI 分析结果 =====");
            actor.sendMessage("§a正常概率: §f" + String.format(Locale.ROOT, "%.1f%%", probs[0] * 100));
            actor.sendMessage("§c作弊概率: §f" + String.format(Locale.ROOT, "%.1f%%", cheatProb * 100));
            if (cheatProb >= core.config().autoPunishThreshold) {
                actor.sendMessage("§c⚠ 高度疑似作弊，已自动处理。");
                core.punishment().handleViolation(target, tracked, "AI举报分析：高度疑似作弊");
            } else if (cheatProb >= core.config().alertThreshold) {
                actor.sendMessage("§e⚠ 可疑行为，请管理员人工观察。");
            } else {
                actor.sendMessage("§a✓ 未检测到明显作弊行为。");
            }
        });
    }

    /**
     * 读 {@code violations/<封禁码>.jsonl} 做统计。
     * <p>
     * ⚠️ 这里用 {@code indexOf("\"pitch\":")} 这样的字符串定位解析，强耦合
     * {@code PunishmentManager#toJsonLine} 的字段名与顺序。改那个格式前先改这里。
     */
    private void handleLookup(CommandActor actor, String code) {
        Path file = core.punishment().violationsFolder().resolve(code + ".jsonl");
        if (!Files.exists(file)) {
            actor.sendMessage("§c未找到封禁码 " + code + " 的记录文件。");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            int totalTicks = lines.size();
            int placingCount = 0;
            double maxPitch = -90;
            double minPitch = 90;
            double maxSpeed = 0;
            for (String line : lines) {
                if (line.isEmpty()) continue;
                if (line.contains("\"placing\":true")) placingCount++;
                Double pitch = readNumber(line, "\"pitch\":");
                if (pitch != null) {
                    if (pitch > maxPitch) maxPitch = pitch;
                    if (pitch < minPitch) minPitch = pitch;
                }
                Double speed = readNumber(line, "\"moveSpeed\":");
                if (speed != null && speed > maxSpeed) maxSpeed = speed;
            }
            actor.sendMessage("§6===== 违规记录 " + code + " =====");
            actor.sendMessage("§7总 tick 数: §f" + totalTicks);
            actor.sendMessage("§7放置方块次数: §f" + placingCount);
            actor.sendMessage("§7最大俯仰角: §f" + String.format(Locale.ROOT, "%.1f", maxPitch) + "°");
            actor.sendMessage("§7最小俯仰角: §f" + String.format(Locale.ROOT, "%.1f", minPitch) + "°");
            actor.sendMessage("§7最大水平速度: §f" + String.format(Locale.ROOT, "%.2f", maxSpeed) + " m/s");
            actor.sendMessage("§7文件路径: §f" + file.toAbsolutePath());
        } catch (IOException e) {
            actor.sendMessage("§c读取记录文件时出错: " + e.getMessage());
        }
    }

    /** 从一行 JSON 里抠出某个数值字段；找不到或格式不对返回 null。 */
    private static Double readNumber(String line, String key) {
        int idx = line.indexOf(key);
        if (idx == -1) return null;
        int start = idx + key.length();
        int end = line.indexOf(',', start);
        if (end == -1) end = line.indexOf('}', start);
        if (end == -1) return null;
        try {
            return Double.parseDouble(line.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
