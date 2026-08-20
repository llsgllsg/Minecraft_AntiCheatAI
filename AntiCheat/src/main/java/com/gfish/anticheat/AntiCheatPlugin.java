package com.gfish.anticheat;

import com.gfish.anticheat.check.BoatSpeedCheck;
import com.gfish.anticheat.check.Check;
import com.gfish.anticheat.check.CheckData;
import com.gfish.anticheat.check.FlyCheck;
import com.gfish.anticheat.check.SpeedCheck;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * DeepGuard 主类 —— 协调者角色（对应 Grim 的 GrimPlugin）。
 * <p>
 * 只负责：生命周期（TrackedPlayer 的创建/销毁）、调度（录制、检查、AI 扫描）、
 * 命令与事件分发。具体检测逻辑下沉到 {@code check} 包，处罚下沉到
 * {@link PunishmentManager}。AI 检测能力（行为录制 -> 特征图 -> ONNX 推理）完整保留。
 */
public final class AntiCheatPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, TrackedPlayer> trackedPlayers = new HashMap<>();
    private final List<Check> checks = new ArrayList<>();

    private AIInferenceEngine aiEngine;
    private PunishmentManager punishmentManager;
    private UpdateManager updateManager;

    private double autoPunishThreshold;
    private double alertThreshold;
    private int analysisSeconds;
    private String punishCmd;
    private String banCmd;
    private boolean aiEnabled;
    private boolean flyCheck;
    private boolean boatSpeedCheck;
    private boolean speedCheckEnabled;
    private double maxBoatSpeed;
    private double maxSpeed;
    private String apPlaceholder;
    private String speedPunishCommand;
    private File violationsFolder;
    private boolean papiEnabled = false;
    private boolean geyserBypassEnabled;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            papiEnabled = true;
            getLogger().info("PlaceholderAPI 已挂接，速度检测将动态计算属性加成。");
        } else {
            getLogger().warning("PlaceholderAPI 未安装，属性加成将始终为 0。");
        }

        violationsFolder = new File(getDataFolder(), "violations");
        if (!violationsFolder.exists()) {
            violationsFolder.mkdirs();
        }

        punishmentManager = new PunishmentManager(this);
        updateManager = new UpdateManager(this);

        if (aiEnabled) {
            loadAiModel();
            // 自动拉取最新模型，管理员无需手动更新模型文件
            updateManager.downloadModelAsync();
        }
        // 启动时异步检测新版本
        updateManager.checkVersionAsync();

        registerChecks();
        getServer().getPluginManager().registerEvents(this, this);

        // 每 tick：录制行为 + 驱动按 tick 的检查
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    TrackedPlayer tracked = getOrCreate(player);
                    tracked.recordTick();
                    for (Check check : checks) {
                        check.tick(player, tracked);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 1L);

        // 每 60 秒：AI 自动扫描
        if (aiEnabled) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player player : getServer().getOnlinePlayers()) {
                        if (isExempted(player)) continue;
                        if (player.hasPermission("deepguard.bypass")) continue;
                        analyzePlayerAsync(player);
                    }
                }
            }.runTaskTimer(this, 1200L, 1200L);
        }

        getLogger().info("DeepGuard (Gfish) 已启用");
    }

    @Override
    public void onDisable() {
        trackedPlayers.clear();
    }

    private void registerChecks() {
        checks.add(new FlyCheck(this));
        checks.add(new BoatSpeedCheck(this));
        checks.add(new SpeedCheck(this));
    }

    // ------------------------------------------------------------------
    // 玩家生命周期
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        getOrCreate(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        trackedPlayers.remove(e.getPlayer().getUniqueId());
    }

    private TrackedPlayer getOrCreate(Player player) {
        TrackedPlayer tracked = trackedPlayers.get(player.getUniqueId());
        if (tracked == null) {
            tracked = new TrackedPlayer(player);
            trackedPlayers.put(player.getUniqueId(), tracked);
        }
        return tracked;
    }

    // ------------------------------------------------------------------
    // 事件分发
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        TrackedPlayer tracked = trackedPlayers.get(e.getPlayer().getUniqueId());
        if (tracked != null) {
            tracked.markPlacing();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        TrackedPlayer tracked = trackedPlayers.get(e.getPlayer().getUniqueId());
        if (tracked != null) {
            tracked.onTeleport();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        TrackedPlayer tracked = trackedPlayers.get(e.getPlayer().getUniqueId());
        if (tracked != null) {
            tracked.onVelocity();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        if (isExempted(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.hasPermission("deepguard.bypass")) return;

        Location to = e.getTo();
        if (to == null) return;

        TrackedPlayer tracked = trackedPlayers.get(player.getUniqueId());
        if (tracked == null) return;

        CheckData data = tracked.getProcessor().processMove(
                e.getFrom(), to, player.isOnGround(),
                tracked.isExempt(ExemptionType.TELEPORT),
                tracked.isExempt(ExemptionType.VELOCITY));

        for (Check check : checks) {
            check.handleMove(player, tracked, data);
        }
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private void loadConfigValues() {
        aiEnabled = getConfig().getBoolean("ai.enabled", true);
        autoPunishThreshold = getConfig().getDouble("ai.auto-punish-threshold", 0.85);
        alertThreshold = getConfig().getDouble("ai.alert-threshold", 0.5);
        analysisSeconds = getConfig().getInt("ai.analysis-seconds", 30);
        punishCmd = getConfig().getString("punish-command",
                "kick %player% §c[DeepGuard] 检测到异常行为 [封禁码: %code%]");
        banCmd = getConfig().getString("ban-command",
                "ban %player% §c[DeepGuard] 多次作弊行为 [封禁码: %code%]");

        flyCheck = getConfig().getBoolean("movement.fly", true);
        boatSpeedCheck = getConfig().getBoolean("movement.boat-speed", true);
        maxBoatSpeed = getConfig().getDouble("movement.max-boat-speed", 12.0);

        speedCheckEnabled = getConfig().getBoolean("movement.speed.enabled", false);
        maxSpeed = getConfig().getDouble("movement.speed.max-speed", 5.0);
        apPlaceholder = getConfig().getString("movement.speed.ap-placeholder", "%ap_moving:max%");
        speedPunishCommand = getConfig().getString("movement.speed.command", "kick %player% §c你移动速度过快！");

        geyserBypassEnabled = getConfig().getBoolean("geyser-bypass.enabled", true);
    }

    /**
     * 判断玩家是否豁免（Geyser 基岩版玩家），通过 UUID 版本检测。
     * UUID version 3 = 基岩版，4 = Java版。
     */
    private boolean isExempted(Player player) {
        return geyserBypassEnabled && player.getUniqueId().version() == 3;
    }

    // ------------------------------------------------------------------
    // AI 检测（保留原功能）
    // ------------------------------------------------------------------

    /**
     * 加载 AI 模型：优先使用数据目录中的模型文件，缺失时从 jar 内置资源解压，开箱即用。
     * 由 UpdateManager 在自动下载完成后调用以重载最新模型。
     */
    public void loadAiModel() {
        if (!aiEnabled) return;
        File modelFile = new File(getDataFolder(), getConfig().getString("ai.model-path", "scaffold_detector.onnx"));
        if (!modelFile.exists() && getResource("scaffold_detector.onnx") != null) {
            saveResource("scaffold_detector.onnx", false);
            getLogger().info("已从内置资源解压模型: " + modelFile.getName());
        }
        if (modelFile.exists()) {
            if (aiEngine == null) {
                aiEngine = new AIInferenceEngine();
            }
            if (aiEngine.loadModel(modelFile.getAbsolutePath())) {
                getLogger().info("AI 模型加载成功");
            } else {
                getLogger().warning("AI 模型加载失败");
            }
        } else {
            getLogger().warning("找不到 AI 模型文件: " + modelFile.getAbsolutePath());
        }
    }

    private void analyzePlayerAsync(Player player) {
        analyzePlayerAsync(player, probs -> {
            float cheatProb = probs[1];
            if (cheatProb >= autoPunishThreshold) {
                getPunishmentManager().handleViolation(player, getOrCreate(player), "AI 定时检测：高度疑似作弊");
            } else if (cheatProb >= alertThreshold) {
                String msg = String.format("§e[AC] §c%s §7AI 定时检测：可疑行为 (%.1f%%)",
                        player.getName(), cheatProb * 100);
                for (Player p : getServer().getOnlinePlayers()) {
                    if (p.hasPermission("deepguard.admin")) p.sendMessage(msg);
                }
                getLogger().info(ChatColor.stripColor(msg));
            }
        });
    }

    private void analyzePlayerAsync(Player player, java.util.function.Consumer<float[]> callback) {
        TrackedPlayer tracked = trackedPlayers.get(player.getUniqueId());
        if (tracked == null) return;

        BehaviorRecorder.BehaviorTick[] ticks = tracked.getRecorder().getRecentTicks(analysisSeconds * 20);
        if (ticks.length < 100) return;

        float[][][] image = BehaviorImageBuilder.buildImage(ticks);
        CompletableFuture.supplyAsync(() -> aiEngine.infer(image))
                .exceptionally(ex -> {
                    getLogger().warning("AI 推理异常: " + ex.getMessage());
                    return null;
                })
                .thenAccept(probs -> {
                    if (probs == null || !isEnabled()) return;
                    Bukkit.getScheduler().runTask(this, () -> callback.accept(probs));
                });
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("deepguard.admin")) {
            sender.sendMessage("§c权限不足。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e/ac report <玩家> §7- AI 分析玩家行为");
            sender.sendMessage("§e/ac lookup <封禁码> §7- 查看违规记录详情");
            sender.sendMessage("§e/ac update §7- 检查更新并同步最新模型");
            sender.sendMessage("§e/ac reload §7- 重载配置");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                reloadConfig();
                loadConfigValues();
                sender.sendMessage("§a配置已重载。");
                break;
            case "report":
                if (args.length < 2) {
                    sender.sendMessage("§c请指定玩家名。");
                    return true;
                }
                Player target = getServer().getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§c玩家不在线。");
                    return true;
                }
                handleReport(sender, target);
                break;
            case "lookup":
                if (args.length < 2) {
                    sender.sendMessage("§c请提供封禁码。");
                    return true;
                }
                handleLookup(sender, args[1]);
                break;
            case "update":
                sender.sendMessage("§6正在检查更新并同步最新模型...");
                updateManager.checkVersionAsync();
                updateManager.downloadModelAsync().thenRun(() ->
                        Bukkit.getScheduler().runTask(this, () ->
                                sender.sendMessage("§a更新检查完成，模型已同步到最新。")));
                break;
            default:
                sender.sendMessage("§c未知子命令。");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("report", "lookup", "reload", "update");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("report")) {
            return null;
        }
        return Collections.emptyList();
    }

    private void handleReport(CommandSender sender, Player target) {
        if (!aiEnabled || aiEngine == null) {
            sender.sendMessage("§cAI 引擎未启用。");
            return;
        }
        TrackedPlayer tracked = trackedPlayers.get(target.getUniqueId());
        if (tracked == null) {
            sender.sendMessage("§c该玩家尚无足够数据。");
            return;
        }
        sender.sendMessage("§6正在分析 " + target.getName() + " 的最近 " + analysisSeconds + " 秒行为...");
        analyzePlayerAsync(target, probs -> {
            float cheatProb = probs[1];
            sender.sendMessage("§6===== AI 分析结果 =====");
            sender.sendMessage("§a正常概率: §f" + String.format("%.1f%%", probs[0] * 100));
            sender.sendMessage("§c作弊概率: §f" + String.format("%.1f%%", cheatProb * 100));
            if (cheatProb >= autoPunishThreshold) {
                sender.sendMessage("§c⚠ 高度疑似作弊，已自动处理。");
                getPunishmentManager().handleViolation(target, tracked, "AI举报分析：高度疑似作弊");
            } else if (cheatProb >= alertThreshold) {
                sender.sendMessage("§e⚠ 可疑行为，请管理员人工观察。");
            } else {
                sender.sendMessage("§a✓ 未检测到明显作弊行为。");
            }
        });
    }

    private void handleLookup(CommandSender sender, String code) {
        File file = new File(violationsFolder, code + ".jsonl");
        if (!file.exists()) {
            sender.sendMessage("§c未找到封禁码 " + code + " 的记录文件。");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            int totalTicks = lines.size();
            int placingCount = 0;
            double maxPitch = -90, minPitch = 90;
            double maxSpeed = 0;
            for (String line : lines) {
                if (line.isEmpty()) continue;
                if (line.contains("\"placing\":true")) placingCount++;
                int pitchIdx = line.indexOf("\"pitch\":");
                if (pitchIdx != -1) {
                    int start = pitchIdx + 8;
                    int end = line.indexOf(',', start);
                    if (end == -1) end = line.indexOf('}', start);
                    double pitch = Double.parseDouble(line.substring(start, end));
                    if (pitch > maxPitch) maxPitch = pitch;
                    if (pitch < minPitch) minPitch = pitch;
                }
                int speedIdx = line.indexOf("\"moveSpeed\":");
                if (speedIdx != -1) {
                    int start = speedIdx + 12;
                    int end = line.indexOf(',', start);
                    if (end == -1) end = line.indexOf('}', start);
                    double speed = Double.parseDouble(line.substring(start, end));
                    if (speed > maxSpeed) maxSpeed = speed;
                }
            }
            sender.sendMessage("§6===== 违规记录 " + code + " =====");
            sender.sendMessage("§7总 tick 数: §f" + totalTicks);
            sender.sendMessage("§7放置方块次数: §f" + placingCount);
            sender.sendMessage("§7最大俯仰角: §f" + String.format("%.1f", maxPitch) + "°");
            sender.sendMessage("§7最小俯仰角: §f" + String.format("%.1f", minPitch) + "°");
            sender.sendMessage("§7最大水平速度: §f" + String.format("%.2f", maxSpeed) + " m/s");
            sender.sendMessage("§7文件路径: §f" + file.getAbsolutePath());
        } catch (IOException e) {
            sender.sendMessage("§c读取记录文件时出错: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 供检查 / 处罚管理器读取的配置
    // ------------------------------------------------------------------

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public boolean isFlyCheckEnabled() {
        return flyCheck;
    }

    public boolean isBoatSpeedCheckEnabled() {
        return boatSpeedCheck;
    }

    public boolean isSpeedCheckEnabled() {
        return speedCheckEnabled;
    }

    public double getMaxBoatSpeed() {
        return maxBoatSpeed;
    }

    public double getMaxSpeed() {
        return maxSpeed;
    }

    public String getApPlaceholder() {
        return apPlaceholder;
    }

    public boolean isPapiEnabled() {
        return papiEnabled;
    }

    public String getSpeedPunishCommand() {
        return speedPunishCommand;
    }

    public String getPunishCommand() {
        return punishCmd;
    }

    public String getBanCommand() {
        return banCmd;
    }

    public int getAnalysisSeconds() {
        return analysisSeconds;
    }

    public File getViolationsFolder() {
        return violationsFolder;
    }
}
