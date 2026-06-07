package com.gfish.anticheat;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
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

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class AntiCheatPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, BehaviorRecorder> recorders = new HashMap<>();
    private final Map<UUID, Integer> flyWarnings = new HashMap<>();

    private AIInferenceEngine aiEngine;
    private double autoPunishThreshold, alertThreshold;
    private int analysisSeconds;
    private String punishCmd;
    private boolean aiEnabled;
    private boolean flyCheck, boatSpeedCheck, waterWalkCheck;
    private double maxBoatSpeed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

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

        // 每 tick 记录行为
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    recorders.computeIfAbsent(player.getUniqueId(), k -> new BehaviorRecorder())
                            .record(captureTick(player));
                }
            }
        }.runTaskTimer(this, 0L, 1L);

        // 每 5 分钟自动 AI 分析
        if (aiEnabled) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player player : getServer().getOnlinePlayers()) {
                        analyzePlayerAsync(player);
                    }
                }
            }.runTaskTimer(this, 6000L, 6000L);
        }

        getLogger().info("AntiCheat (Gfish) 已启用");
    }

    private void loadConfigValues() {
        aiEnabled = getConfig().getBoolean("ai.enabled", true);
        autoPunishThreshold = getConfig().getDouble("ai.auto-punish-threshold", 0.85);
        alertThreshold = getConfig().getDouble("ai.alert-threshold", 0.5);
        analysisSeconds = getConfig().getInt("ai.analysis-seconds", 30);
        punishCmd = getConfig().getString("punish-command", "kick %player% §c[AntiCheat] 检测到异常行为");

        flyCheck = getConfig().getBoolean("movement.fly", true);
        boatSpeedCheck = getConfig().getBoolean("movement.boat-speed", true);
        maxBoatSpeed = getConfig().getDouble("movement.max-boat-speed", 12.0);
        waterWalkCheck = getConfig().getBoolean("movement.water-walk", true);
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
        recorders.remove(e.getPlayer().getUniqueId());
        flyWarnings.remove(e.getPlayer().getUniqueId());
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
        if (waterWalkCheck) checkWaterWalk(player, to);
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
                alertAndPunish(player, "飞行/悬空");
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
            alertAndPunish(player, String.format("异常船速 (%.1f m/s)", speed));
        }
    }

    private void checkWaterWalk(Player player, Location to) {
        Block feetBlock = to.getBlock();
        Block below = feetBlock.getRelative(0, -1, 0);
        if (below.getType() != Material.WATER) return;

        if (player.isFlying() || player.isGliding() || player.isInsideVehicle() ||
                player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE) ||
                player.hasPotionEffect(PotionEffectType.LEVITATION)) return;

        if (player.getEquipment() != null && player.getEquipment().getBoots() != null) {
            if (player.getEquipment().getBoots().containsEnchantment(Enchantment.DEPTH_STRIDER) ||
                    player.getEquipment().getBoots().containsEnchantment(Enchantment.FROST_WALKER)) return;
        }

        alertAndPunish(player, "水上行走");
    }

    private void alertAndPunish(Player player, String reason) {
        String msg = String.format("§e[AC] §c%s §7%s", player.getName(), reason);
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.hasPermission("anticheat.admin")) {
                p.sendMessage(msg);
            }
        }
        getLogger().info(ChatColor.stripColor(msg));
        executePunish(player);
    }

    private void executePunish(Player player) {
        String cmd = punishCmd.replace("%player%", player.getName());
        getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
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
                sender.sendMessage("§c⚠ 高度疑似作弊，已自动处罚。");
                executePunish(target);
            } else if (cheatProb >= alertThreshold) {
                sender.sendMessage("§e⚠ 可疑行为，请管理员人工观察。");
            } else {
                sender.sendMessage("§a✓ 未检测到明显作弊行为。");
            }
        });
    }

    /**
     * 定时自动分析（静默，仅高置信或可疑时广播）
     */
    private void analyzePlayerAsync(Player player) {
        analyzePlayerAsync(player, probs -> {
            float cheatProb = probs[1];
            if (cheatProb >= autoPunishThreshold) {
                alertAndPunish(player, "AI 定时检测：高度疑似作弊");
            } else if (cheatProb >= alertThreshold) {
                String msg = String.format("§e[AC] §c%s §7AI 定时检测：可疑行为 (%.1f%%)",
                        player.getName(), cheatProb * 100);
                for (Player p : getServer().getOnlinePlayers()) {
                    if (p.hasPermission("anticheat.admin")) {
                        p.sendMessage(msg);
                    }
                }
                getLogger().info(ChatColor.stripColor(msg));
            }
        });
    }

    /**
     * 异步 AI 分析核心方法
     */
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
            return Arrays.asList("report", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("report")) {
            return null;
        }
        return Collections.emptyList();
    }
}