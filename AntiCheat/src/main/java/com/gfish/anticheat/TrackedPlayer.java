package com.gfish.anticheat;

import com.gfish.anticheat.check.MovementProcessor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/**
 * 每玩家数据对象（对应 Grim 的 GrimPlayer）。
 * <p>
 * 持有该玩家的所有运行时状态：
 * - 行为录制器（AI 特征输入）
 * - 移动处理器（把原始移动事件转成 CheckData）
 * - 各检查的状态（飞行警告数、速度基线）
 * - 豁免计时（传送/击退宽限期）
 * - 违规时间戳（处罚累进用）
 * <p>
 * 生命周期由 AntiCheatPlugin 在 join/quit 时创建与销毁，取代原先散落在
 * 主类里的多个 UUID -> 状态 的 Map。
 */
public final class TrackedPlayer {

    private final UUID uuid;
    private final BehaviorRecorder recorder = new BehaviorRecorder();
    private final MovementProcessor processor = new MovementProcessor();
    private final List<Long> cheatTimestamps = new ArrayList<>();
    private final EnumMap<ExemptionType, Long> exemptionExpiry = new EnumMap<>(ExemptionType.class);

    private Player player;
    private int flyWarnings;
    private Location lastSpeedLocation;
    private boolean placingThisTick;

    public TrackedPlayer(Player player) {
        this.uuid = player.getUniqueId();
        this.player = player;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Player getPlayer() {
        return player;
    }

    public BehaviorRecorder getRecorder() {
        return recorder;
    }

    public MovementProcessor getProcessor() {
        return processor;
    }

    public List<Long> getCheatTimestamps() {
        return cheatTimestamps;
    }

    public int getFlyWarnings() {
        return flyWarnings;
    }

    public void setFlyWarnings(int flyWarnings) {
        this.flyWarnings = flyWarnings;
    }

    public Location getLastSpeedLocation() {
        return lastSpeedLocation;
    }

    public void setLastSpeedLocation(Location lastSpeedLocation) {
        this.lastSpeedLocation = lastSpeedLocation;
    }

    // ---------- 豁免 ----------

    /** 为指定类型添加持续 millis 毫秒的豁免。 */
    public void exempt(ExemptionType type, long millis) {
        exemptionExpiry.put(type, System.currentTimeMillis() + millis);
    }

    public boolean isExempt(ExemptionType type) {
        Long expiry = exemptionExpiry.get(type);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public boolean isExemptAny(ExemptionType... types) {
        for (ExemptionType type : types) {
            if (isExempt(type)) return true;
        }
        return false;
    }

    // ---------- 状态更新 ----------

    /** 传送后调用：重置移动基线并进入传送宽限期。 */
    public void onTeleport() {
        processor.reset(player.getLocation());
        exempt(ExemptionType.TELEPORT, 1500);
    }

    /** 速度被改变（击退/加速）后调用：重置移动基线并进入宽限期。 */
    public void onVelocity() {
        processor.reset(player.getLocation());
        exempt(ExemptionType.VELOCITY, 1000);
    }

    /** 方块放置事件回调：把"本 tick 放置"标记进下一个录制 tick。 */
    public void markPlacing() {
        placingThisTick = true;
    }

    /**
     * 每 tick 录制一个行为快照。
     * <p>
     * 与 recorder-plugin 保持一致，moveSpeed 取水平速度（排除垂直分量），
     * 这样在线检测与训练数据使用相同的特征定义。
     */
    public void recordTick() {
        Player p = player;
        Location loc = p.getLocation();
        Vector vel = p.getVelocity();

        BehaviorRecorder.BehaviorTick tick = new BehaviorRecorder.BehaviorTick();
        tick.timestamp = System.currentTimeMillis();
        tick.pitch = (float) loc.getPitch();
        tick.yaw = (float) loc.getYaw();
        tick.posX = loc.getX();
        tick.posY = loc.getY();
        tick.posZ = loc.getZ();
        tick.placing = placingThisTick;
        tick.sprinting = p.isSprinting();
        tick.onGround = p.isOnGround();
        tick.jumping = !p.isOnGround();
        tick.moveSpeed = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());
        tick.vertSpeed = vel.getY();
        placingThisTick = false;

        recorder.record(tick);
    }
}
