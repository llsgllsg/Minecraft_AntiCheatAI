package com.example.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorRecorderPlugin extends JavaPlugin implements Listener {

    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Map<UUID, Recorder> recorders = new ConcurrentHashMap<>();
    private File saveFolder;
    private boolean autoRecord;
    private int bufferSize;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        autoRecord = getConfig().getBoolean("auto-record", true);
        bufferSize = getConfig().getInt("buffer-size", 256);
        saveFolder = new File(getDataFolder(), getConfig().getString("save-dir", "recordings"));
        if (!saveFolder.exists()) saveFolder.mkdirs();

        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    Recorder recorder = recorders.get(player.getUniqueId());
                    if (recorder == null) continue;
                    if (recorder.isRecording()) {
                        recorder.recordTick(player);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    @Override
    public void onDisable() {
        for (Recorder recorder : recorders.values()) {
            recorder.stopAndFlush();
        }
        recorders.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        recorders.put(player.getUniqueId(), new Recorder(player, this));
        if (autoRecord) {
            recorders.get(player.getUniqueId()).startAuto();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        Recorder recorder = recorders.remove(uuid);
        if (recorder != null) {
            recorder.stopAndFlush();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        Recorder recorder = recorders.get(e.getPlayer().getUniqueId());
        if (recorder != null) {
            recorder.markPlaced();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }
        if (!sender.hasPermission("recorder.admin")) {
            sender.sendMessage("§c你没有权限。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e用法: /record <normal|cheat|stop>");
            return true;
        }

        Player player = (Player) sender;
        Recorder recorder = recorders.get(player.getUniqueId());
        if (recorder == null) {
            sender.sendMessage("§c内部错误：没有找到你的录制器。");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "normal":
                recorder.startManual(0);
                sender.sendMessage("§a开始录制 §e正常行为 §a(标签 0)");
                break;
            case "cheat":
                recorder.startManual(1);
                sender.sendMessage("§c开始录制 §e作弊行为 §c(标签 1)");
                break;
            case "stop":
                recorder.stopManual();
                sender.sendMessage("§7手动录制已停止。数据已保存。");
                break;
            default:
                sender.sendMessage("§c无效参数。请使用 normal/cheat/stop");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("normal", "cheat", "stop");
        }
        return Collections.emptyList();
    }

    private static class Recorder {
        private final Player player;
        private final BehaviorRecorderPlugin plugin;
        private final List<BehaviorTick> buffer = new ArrayList<>();
        private int currentLabel = 0;
        private boolean autoRecording = false;
        private boolean manualRecording = false;
        private long sessionStartTime;
        private boolean placedFlag = false;

        public Recorder(Player player, BehaviorRecorderPlugin plugin) {
            this.player = player;
            this.plugin = plugin;
        }

        public boolean isRecording() {
            return autoRecording || manualRecording;
        }

        public void startAuto() {
            if (manualRecording) return;
            autoRecording = true;
            currentLabel = 0;
            sessionStartTime = System.currentTimeMillis();
            buffer.clear();
            placedFlag = false;
        }

        public void startManual(int label) {
            if (autoRecording) {
                saveBufferToFile();
                autoRecording = false;
            }
            if (manualRecording) {
                saveBufferToFile();
            }
            manualRecording = true;
            currentLabel = label;
            sessionStartTime = System.currentTimeMillis();
            buffer.clear();
            placedFlag = false;
        }

        public void stopManual() {
            if (manualRecording) {
                saveBufferToFile();
                manualRecording = false;
                if (plugin.autoRecord) {
                    startAuto();
                }
            }
        }

        public void stopAndFlush() {
            if (autoRecording || manualRecording) {
                saveBufferToFile();
            }
            autoRecording = false;
            manualRecording = false;
            buffer.clear();
        }

        public void markPlaced() {
            placedFlag = true;
        }

        public void recordTick(Player player) {
            if (player.getGameMode() == GameMode.SPECTATOR) return;

            Location loc = player.getLocation();
            BehaviorTick tick = new BehaviorTick();
            tick.timestamp = System.currentTimeMillis();
            tick.pitch = loc.getPitch();
            tick.yaw = loc.getYaw();
            tick.posX = loc.getX();
            tick.posY = loc.getY();
            tick.posZ = loc.getZ();
            tick.placing = placedFlag;
            tick.sprinting = player.isSprinting();
            tick.onGround = player.isOnGround();
            tick.jumping = !player.isOnGround();
            org.bukkit.util.Vector vel = player.getVelocity();
            tick.moveSpeed = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());
            tick.vertSpeed = vel.getY();

            buffer.add(tick);
            placedFlag = false;

            if (buffer.size() >= plugin.bufferSize) {
                saveBufferToFile();
                buffer.clear();
                sessionStartTime = System.currentTimeMillis();
            }
        }

        private void saveBufferToFile() {
            if (buffer.isEmpty()) return;

            String fileName = String.format("%s_%d_%d.jsonl",
                    player.getUniqueId(),
                    sessionStartTime,
                    currentLabel);
            File file = new File(plugin.saveFolder, fileName);

            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (BehaviorTick tick : buffer) {
                    writer.write(plugin.gson.toJson(tick));
                    writer.newLine();
                }
            } catch (IOException e) {
                plugin.getLogger().warning("保存录制文件失败: " + e.getMessage());
            }
        }
    }

    public static class BehaviorTick {
        public long timestamp;
        public float pitch;
        public float yaw;
        public double posX;
        public double posY;
        public double posZ;
        public boolean placing;
        public boolean sprinting;
        public boolean jumping;
        public boolean onGround;
        public double moveSpeed;
        public double vertSpeed;
    }
}