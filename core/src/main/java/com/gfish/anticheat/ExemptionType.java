package com.gfish.anticheat;

/**
 * 豁免类型（对应 Grim 的 ExemptionType）。
 * 用于在特定时间内跳过检测，避免传送 / 击退等合法行为被误判。
 */
public enum ExemptionType {
    /** 传送（末影珍珠、/tp、传送门等）后的宽限期 */
    TELEPORT,
    /** 速度变化（击退、鞘翅加速等）后的宽限期 */
    VELOCITY
}
