package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.platform.Pos;
import com.gfish.anticheat.mod.mixin.PlayerInvoker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * 逐字复刻 CraftBukkit 的 {@code PlayerMoveEvent} 发射逻辑。每个玩家一份。
 * <p>
 * <b>为什么必须复刻而不是"每个移动包都喂给内核"</b>：
 * {@code MovementProcessor} 用相邻两次事件的<b>墙上时间差</b>算速度，所以事件
 * <b>频率</b>直接决定速度值。多发事件 → dt 偏小 → 速度虚高 → 误报；
 * 漏发 → dt 偏大 → 速度偏低 → 漏检。两端必须严格同频。
 * <p>
 * CraftBukkit 的原逻辑：
 * <pre>
 *   delta      = sq(lastPosX - to.x) + sq(lastPosY - to.y) + sq(lastPosZ - to.z)
 *   deltaAngle = abs(lastYaw - to.yaw) + abs(lastPitch - to.pitch)
 *   if ((delta &gt; 1f/256 || deltaAngle &gt; 10f) &amp;&amp; !player.isImmobile()) { 触发 }
 * </pre>
 * 触发时 {@code from} 取<b>上一次触发</b>时存的坐标（不是玩家当前位置），
 * {@code to} 取本包解析出的坐标，并在触发后把基线更新为 {@code to}。
 * 未触发时基线<b>不动</b>，所以位移会累积到下一次触发 —— 这一点很关键，
 * 抄漏了会让慢速移动的玩家永远发不出事件。
 * <p>
 * ⚠️ 26.2 的服务端里<b>没有</b> {@code lastPosX/Y/Z} 字段（那是 CraftBukkit
 * 补丁加的），所以基线必须由本类自己维护。
 */
public final class MoveEventEmitter {

    /** CraftBukkit 用的死区阈值：位移平方和 > 1/256，或角度变化和 > 10°。 */
    private static final double DELTA_THRESHOLD = 1.0 / 256.0;
    private static final float ANGLE_THRESHOLD = 10.0f;

    private boolean seeded;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;

    /** 一次成功触发的移动事件。 */
    public record Emission(Pos from, Pos to, boolean onGround) {
    }

    /**
     * 把基线设成玩家当前位置。
     * <p>
     * 新玩家加入、以及传送后都要调用 —— CraftBukkit 在 {@code resetPosition()} 里
     * 做同样的事。漏了传送这一次，下一个包会相对旧基线算出一个巨大的位移
     * （虽然内核侧有 1500ms 传送豁免兜底，但事件本身就不该发出来）。
     */
    public void reset(ServerPlayer player) {
        this.lastX = player.getX();
        this.lastY = player.getY();
        this.lastZ = player.getZ();
        this.lastYaw = player.getYRot();
        this.lastPitch = player.getXRot();
        this.seeded = true;
    }

    /**
     * 处理一个移动包，返回是否应当向内核派发事件。
     *
     * @param toX     包里的 X；包没有位置时传玩家当前 X（与 CraftBukkit 的
     *                {@code packet.getX(player.getX())} 等价）
     * @param onGround 包里的 onGround —— 必须用包里的，不能用注入点读到的旧值
     * @return 触发则返回事件，被死区滤掉则返回 null
     */
    public Emission evaluate(ServerPlayer player, double toX, double toY, double toZ,
                             float toYaw, float toPitch, boolean onGround) {
        if (!seeded) {
            reset(player);
        }

        double delta = Mth.square(lastX - toX) + Mth.square(lastY - toY) + Mth.square(lastZ - toZ);
        float deltaAngle = Math.abs(lastYaw - toYaw) + Math.abs(lastPitch - toPitch);

        // 死区：微小的位移和转头不触发，基线也不推进
        if (delta <= DELTA_THRESHOLD && deltaAngle <= ANGLE_THRESHOLD) {
            return null;
        }
        if (isImmobile(player)) {
            return null;
        }

        Pos from = new Pos(lastX, lastY, lastZ, lastYaw, lastPitch);
        Pos to = new Pos(toX, toY, toZ, toYaw, toPitch);

        lastX = toX;
        lastY = toY;
        lastZ = toZ;
        lastYaw = toYaw;
        lastPitch = toPitch;

        return new Emission(from, to, onGround);
    }

    /**
     * {@code Player#isImmobile()} 是 protected，只能经 Mixin 生成的调用器访问
     * （见 {@link com.gfish.anticheat.mod.mixin.PlayerInvoker}）。
     * 不用"干脆不判"来绕过：死亡 / 睡眠中的玩家在 CraftBukkit 侧不产生事件，
     * 少了这一条就会凭空多出事件，两端频率又对不上了。
     */
    private static boolean isImmobile(ServerPlayer player) {
        return ((PlayerInvoker) (Player) player).deepguard$isImmobile();
    }
}
