package com.gfish.anticheat;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class AntiCheatPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, BehaviorRecorder> recorders = new HashMap<>();
    private final Map<UUID, Integer> flyWarnings = new HashMap<>();
    private final Map<UUID, List<Long>> cheatTimestamps = new HashMap<>();

    private AIInferenceEngine aiEngine;
    private double autoPunishThreshold, alertThreshold;
    private int analysisSeconds;
    private String punishCmd;
    private String banCmd;
    private boolean aiEnabled;
    private boolean flyCheck, boatSpeedCheck;
    private double maxBoatSpeed;
    private File violationsFolder; // 封禁记录保存目录

    private static final long VIOLATION_WINDOW_MS = 5 * 60 * 1000;
    private static final int MAX_KICKS = 3;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        // 创建记录目录
        violationsFolder = new File(getDataFolder(), "violations");
        if (!violationsFolder.exists()) violationsFolder.mkdirs();

        if (aiEnabled) {
            aiEngine = new AIInferenceEngine();
            File modelFile = new File(getDataFolder(), getConfig().getString("ai.model-path", "scaffold_detector.onnx"));
            if (modelFile.exists()) {
                if (aiEngine.loadModel(modelFile.getAbsolutePath())) {
                    getLogger().info("AI 模型加载成功");
                } else {
                    getLogger().warning("AI 模型加载失败");
                }
            } else {
                getLogger().warning("找不到 AI 模型文件: " + modelFile.getAbsolutePath());
            }
        }

        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    recorders.computeIfAbsent(player.getUniqueId(), k -> new BehaviorRecorder())
                            .record(captureTick(player));
                }
            }
        }.runTaskTimer(this, 0L, 1L);

        if (aiEnabled) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player player : getServer().getOnlinePlayers()) {
                        analyzePlayerAsync(player);
                    }
                }
            }.runTaskTimer(this, 1200L, 1200L);
        }

        getLogger().info("AntiCheat (Gfish) 已启用");
    }

    private void loadConfigValues() {
        aiEnabled = getConfig().getBoolean("ai.enabled", true);
        autoPunishThreshold = getConfig().getDouble("ai.auto-punish-threshold", 0.85);
        alertThreshold = getConfig().getDouble("ai.alert-threshold", 0.5);
        analysisSeconds = getConfig().getInt("ai.analysis-seconds", 30);
        punishCmd = getConfig().getString("punish-command", "kick %player% §c[AntiCheat] 检测到异常行为 [封禁码: %code%]");
        banCmd = getConfig().getString("ban-command", "ban %player% §c[AntiCheat] 多次作弊行为 [封禁码: %code%]");

        flyCheck = getConfig().getBoolean("movement.fly", true);
        boatSpeedCheck = getConfig().getBoolean("movement.boat-speed", true);
        maxBoatSpeed = getConfig().getDouble("movement.max-boat-speed", 12.0);
    }

    private BehaviorRecorder.BehaviorTick captureTick(Player player) {
        BehaviorRecorder.BehaviorTick tick = new BehaviorRecorder.BehaviorTick();
        Location loc = player.getLocation();
        tick.timestamp = System.currentTimeMillis();
        tick.pitch = loc.getPitch();
        tick.yaw = loc.getYaw();
        tick.posX = loc.getX();
        tick.posY = loc.getY();
        tick.posZ = loc.getZ();
        tick.placing = false;
        tick.sprinting = player.isSprinting();
        tick.onGround = player.isOnGround();
        tick.jumping = !player.isOnGround();
        tick.moveSpeed = player.getVelocity().length();
        tick.vertSpeed = player.getVelocity().getY();
        return tick;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        BehaviorRecorder rec = recorders.get(e.getPlayer().getUniqueId());
        if (rec != null) {
            BehaviorRecorder.BehaviorTick[] recent = rec.getRecentTicks(1);
            if (recent.length > 0) {
                recent[0].placing = true;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        recorders.remove(uuid);
        flyWarnings.remove(uuid);
        cheatTimestamps.remove(uuid);
    }

    // ================= 基础反作弊 =================
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        Location to = e.getTo();
        if (to == null) return;

        if (flyCheck) checkFly(player, to);
        if (boatSpeedCheck) checkBoatSpeed(player, e.getFrom(), to);
    }

    private void checkFly(Player player, Location to) {
        boolean legal = player.isFlying() || player.isGliding() ||
                player.isInsideVehicle() ||
                player.isInWater() || player.isInLava() ||
                player.isOnGround() ||
                player.hasPotionEffect(PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        Block below = to.clone().subtract(0, 0.1, 0).getBlock();
        if (!below.getType().isAir() && below.getType().isSolid()) {
            legal = true;
        }

        if (!legal) {
            UUID uuid = player.getUniqueId();
            int warnings = flyWarnings.getOrDefault(uuid, 0) + 1;
            flyWarnings.put(uuid, warnings);
            if (warnings >= 60) {
                flyWarnings.put(uuid, 0);
                smartPunish(player, "飞行/悬空");
            }
        } else {
            flyWarnings.put(player.getUniqueId(), 0);
        }
    }

    private void checkBoatSpeed(Player player, Location from, Location to) {
        if (!player.isInsideVehicle()) return;
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat)) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double speed = dist / 0.05;

        if (speed > maxBoatSpeed) {
            smartPunish(player, String.format("异常船速 (%.1f m/s)", speed));
        }
    }

    // ================= 处罚逻辑（含封禁码） =================
    private void smartPunish(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // 记录违规时间
        cheatTimestamps.computeIfAbsent(uuid, k -> new ArrayList<>()).add(now);

        // 生成唯一封禁码（8位，便于手动输入）
        String code = UUID.randomUUID().toString().substring(0, 8);
        // 保存行为记录文件
        saveViolationRecord(player, code);

        // 统计5分钟内的违规次数
        int recentViolations = countRecentViolations(uuid, now, VIOLATION_WINDOW_MS);

        String fullReason = reason + " [封禁码: " + code + "]";
        if (recentViolations > MAX_KICKS) {
            executeBan(player, fullReason, code);
        } else {
            executeKick(player, fullReason, code);
        }

        // 清理过期时间戳
        cleanupOldTimestamps(uuid, now, VIOLATION_WINDOW_MS);
    }

    private int countRecentViolations(UUID uuid, long now, long windowMs) {
        List<Long> timestamps = cheatTimestamps.getOrDefault(uuid, Collections.emptyList());
        long cutoff = now - windowMs;
        return (int) timestamps.stream().filter(t -> t >= cutoff).count();
    }

    private void cleanupOldTimestamps(UUID uuid, long now, long windowMs) {
        List<Long> timestamps = cheatTimestamps.get(uuid);
        if (timestamps != null) {
            long cutoff = now - windowMs;
            timestamps.removeIf(t -> t < cutoff);
            if (timestamps.isEmpty()) cheatTimestamps.remove(uuid);
        }
    }

    /**
     * 保存玩家最近 30 秒的行为记录到 violations/<code>.jsonl
     */
    private void saveViolationRecord(Player player, String code) {
        BehaviorRecorder rec = recorders.get(player.getUniqueId());
        if (rec == null) return;
        BehaviorRecorder.BehaviorTick[] ticks = rec.getRecentTicks(analysisSeconds * 20);
        if (ticks.length == 0) return;

        File file = new File(violationsFolder, code + ".jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (BehaviorRecorder.BehaviorTick tick : ticks) {
                writer.write(toJsonLine(tick));
                writer.newLine();
            }
        } catch (IOException e) {
            getLogger().warning("无法保存违规记录文件: " + e.getMessage());
        }
    }

    private String toJsonLine(BehaviorRecorder.BehaviorTick t) {
        return String.format(
            "{\"ts\":%d,\"pitch\":%.2f,\"yaw\":%.2f," +
            "\"posX\":%.2f,\"posY\":%.2f,\"posZ\":%.2f," +
            "\"placing\":%b,\"sprinting\":%b,\"jumping\":%b," +
            "\"onGround\":%b,\"moveSpeed\":%.3f,\"vertSpeed\":%.3f}",
            t.timestamp, t.pitch, t.yaw,
            t.posX, t.posY, t.posZ,
            t.placing, t.sprinting, t.jumping,
            t.onGround, t.moveSpeed, t.vertSpeed
        );
    }

    private void executeKick(Player player, String reason, String code) {
        String cmd = punishCmd.replace("%player%", player.getName()).replace("%code%", code);
        getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
        getLogger().info("踢出 " + player.getName() + " 封禁码: " + code + " 原因: " + reason);
    }

    private void executeBan(Player player, String reason, String code) {
        String cmd = banCmd.replace("%player%", player.getName()).replace("%code%", code);
        getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
        getLogger().info("封禁 " + player.getName() + " 封禁码: " + code + " 原因: " + reason);
    }

    // ================= 命令 =================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage("§c权限不足。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e/ac report <玩家> §7- AI 分析玩家行为");
            sender.sendMessage("§e/ac lookup <封禁码> §7- 查看违规记录详情");
            sender.sendMessage("§e/ac reload §7- 重载配置");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfigValues();
            sender.sendMessage("§a配置已重载。");
            return true;
        }
        if (args[0].equalsIgnoreCase("report") && args.length >= 2) {
            Player target = getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§c玩家不在线。");
                return true;
            }
            handleReport(sender, target);
            return true;
        }
        if (args[0].equalsIgnoreCase("lookup") && args.length >= 2) {
            handleLookup(sender, args[1]);
            return true;
        }
        return false;
    }

    private void handleReport(CommandSender sender, Player target) {
        if (!aiEnabled || aiEngine == null) {
            sender.sendMessage("§cAI 引擎未启用。");
            return;
        }
        BehaviorRecorder rec = recorders.get(target.getUniqueId());
        if (rec == null) {
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
                smartPunish(target, "AI举报分析：高度疑似作弊");
            } else if (cheatProb >= alertThreshold) {
                sender.sendMessage("§e⚠ 可疑行为，请管理员人工观察。");
            } else {
                sender.sendMessage("§a✓ 未检测到明显作弊行为。");
            }
        });
    }

    /**
     * 查询封禁码对应的记录文件
     */
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
                if (!line.isEmpty()) {
                    // 简单解析（避免复杂JSON解析）
                    if (line.contains("\"placing\":true")) placingCount++;
                    // 提取pitch
                    int pitchIdx = line.indexOf("\"pitch\":");
                    if (pitchIdx != -1) {
                        int start = pitchIdx + 8;
                        int end = line.indexOf(',', start);
                        if (end == -1) end = line.indexOf('}', start);
                        double pitch = Double.parseDouble(line.substring(start, end));
                        if (pitch > maxPitch) maxPitch = pitch;
                        if (pitch < minPitch) minPitch = pitch;
                    }
                    // 提取moveSpeed
                    int speedIdx = line.indexOf("\"moveSpeed\":");
                    if (speedIdx != -1) {
                        int start = speedIdx + 12;
                        int end = line.indexOf(',', start);
                        if (end == -1) end = line.indexOf('}', start);
                        double speed = Double.parseDouble(line.substring(start, end));
                        if (speed > maxSpeed) maxSpeed = speed;
                    }
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

    // ================= AI 分析 =================
    private void analyzePlayerAsync(Player player) {
        analyzePlayerAsync(player, probs -> {
            float cheatProb = probs[1];
            if (cheatProb >= autoPunishThreshold) {
                smartPunish(player, "AI 定时检测：高度疑似作弊");
            } else if (cheatProb >= alertThreshold) {
                String msg = String.format("§e[AC] §c%s §7AI 定时检测：可疑行为 (%.1f%%)",
                        player.getName(), cheatProb * 100);
                for (Player p : getServer().getOnlinePlayers()) {
                    if (p.hasPermission("anticheat.admin")) p.sendMessage(msg);
                }
                getLogger().info(ChatColor.stripColor(msg));
            }
        });
    }

    private void analyzePlayerAsync(Player player, java.util.function.Consumer<float[]> callback) {
        BehaviorRecorder rec = recorders.get(player.getUniqueId());
        if (rec == null) return;
        BehaviorRecorder.BehaviorTick[] ticks = rec.getRecentTicks(analysisSeconds * 20);
        if (ticks.length < 100) return;
        float[][][] image = BehaviorImageBuilder.buildImage(ticks);
        CompletableFuture.supplyAsync(() -> aiEngine.infer(image))
                .thenAccept(probs -> {
                    Bukkit.getScheduler().runTask(this, () -> callback.accept(probs));
                });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("report", "lookup", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("report")) {
            return null; // 玩家列表
        }
        return Collections.emptyList();
    }
}