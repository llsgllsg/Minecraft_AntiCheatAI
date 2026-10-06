package com.gfish.anticheat.core.platform;

/**
 * 日志 SPI。Paper 走 {@code java.util.logging}，模组走 SLF4J / Log4j —— 内核两头都不认。
 * <p>
 * 刻意只留三个级别：多出来的级别没有平台能稳定对应，反而会让同一句日志
 * 在三端落到不同地方。
 */
public interface DgLogger {

    void info(String message);

    void warn(String message);

    void severe(String message);
}
