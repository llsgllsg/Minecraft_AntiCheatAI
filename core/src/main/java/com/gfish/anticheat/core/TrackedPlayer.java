package com.gfish.anticheat.core;

import com.gfish.anticheat.BehaviorRecorder;
import com.gfish.anticheat.ExemptionType;
import com.gfish.anticheat.core.check.MovementProcessor;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.platform.Pos;
import com.gfish.anticheat.core.platform.WorldKey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 每玩家数据对象（对应 Grim 的 GrimPlayer）。
 * <p>
 * 持有该玩家的所有运行时状态：行为录制器、移动处理器、各检查的状态
 * （飞行警告数、速度基线）、豁免计时（传送/击退宽限期）、违规时间戳（处罚累进）。
 * <p>
 * 生命周期由 {@link DeepGuardCore} 在 join/quit 时创建与销毁。
 * <p>
 * 时间统一走注入的 {@code clock}：线上默认 {@code System::currentTimeMillis}
 * （墙上时钟，与 Paper 版一致），测试注入假时钟以便复现毫秒级边界。
 */
public final class TrackedPlayer {

    private final UUID uuid;
    private final LongSupplier clock;
    private final BehaviorRecorder recorder = new BehaviorRecorder();
    private final MovementProcessor processor;
    private final List<Long> cheatTimestamps = new ArrayList<>();
    private final EnumMap<ExemptionType, Long> exemptionExpiry = new EnumMap<>(ExemptionType.class);

    private PlayerHandle player;
    private int flyWarnings;
    private Pos lastSpeedPos;
    private WorldKey lastSpeedWorld;
    private long lastSpeedSampleTime;
    private boolean placingThisTick;

    public TrackedPlayer(PlayerHandle player) {
        this(player, System::currentTimeMillis);
    }

    public TrackedPlayer(PlayerHandle player, LongSupplier clock) {
        this.uuid = player.uuid();
        this.player = player;
        this.clock = clock;
        this.processor = new MovementProcessor(clock);
    }

    public UUID getUuid() {
        return uuid;
    }

    public PlayerHandle getPlayer() {
        return player;
    }

    /**
     * 换成新的 handle。
     * <p>
     * 模组端玩家死亡重生 / 换维度后 {@code ServerPlayer} 实例会被整个替换，
     * 缓存旧的会读到过期的位置与状态 —— 所以每次 {@code getOrCreate} 都要刷新，
     * 平台还要在 clone 事件上显式调一次。
     */
    public void refresh(PlayerHandle newHandle) {
        this.player = newHandle;
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

    public Pos getLastSpeedPos() {
        return lastSpeedPos;
    }

    public WorldKey getLastSpeedWorld() {
        return lastSpeedWorld;
    }

    public void setLastSpeedPos(Pos pos, WorldKey world) {
        this.lastSpeedPos = pos;
        this.lastSpeedWorld = world;
    }

    /** 上次速度采样的时间戳（毫秒）。必须每玩家一份，否则所有玩家会共用同一个降频计时。 */
    public long getLastSpeedSampleTime() {
        return lastSpeedSampleTime;
    }

    public void setLastSpeedSampleTime(long lastSpeedSampleTime) {
        this.lastSpeedSampleTime = lastSpeedSampleTime;
    }

    // ---------- 豁免 ----------

    /** 为指定类型添加持续 millis 毫秒的豁免。 */
    public void exempt(ExemptionType type, long millis) {
        exemptionExpiry.put(type, clock.getAsLong() + millis);
    }

    public boolean isExempt(ExemptionType type) {
        Long expiry = exemptionExpiry.get(type);
        return expiry != null && expiry > clock.getAsLong();
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
        processor.reset();
        exempt(ExemptionType.TELEPORT, 1500);
    }

    /** 速度被改变（击退/加速）后调用：重置移动基线并进入宽限期。 */
    public void onVelocity() {
        processor.reset();
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
     * <p>
     * ⚠️ {@code jumping} 取的是 {@code !onGround()} —— 名字与含义不符，
     * 但训练数据就是这么录的，必须照抄，改了会让模型输入分布漂移。
     */
    public void recordTick() {
        PlayerHandle p = player;
        Pos loc = p.pos();

        BehaviorRecorder.BehaviorTick tick = new BehaviorRecorder.BehaviorTick();
        tick.timestamp = clock.getAsLong();
        tick.pitch = loc.pitch();
        tick.yaw = loc.yaw();
        tick.posX = loc.x();
        tick.posY = loc.y();
        tick.posZ = loc.z();
        tick.placing = placingThisTick;
        tick.sprinting = p.sprinting();
        tick.onGround = p.onGround();
        tick.jumping = !p.onGround();
        tick.moveSpeed = Math.sqrt(p.velocityX() * p.velocityX() + p.velocityZ() * p.velocityZ());
        tick.vertSpeed = p.velocityY();

        // 载具 / 鞘翅：用于区分「合法滞空」与「飞行外挂」（特征通道 12/13）
        tick.inVehicle = p.insideVehicle();
        tick.vehicleType = p.vehicleTypeKey();
        tick.gliding = p.gliding();

        placingThisTick = false;

        recorder.record(tick);
    }
}
