package com.gfish.anticheat.mod;

import com.gfish.anticheat.core.platform.DgLogger;
import org.slf4j.Logger;

/**
 * 内核日志接口接到 SLF4J 上。
 * <p>
 * Fabric（fabric-loader 自带）与 NeoForge（FML 自带）的类路径上都有 SLF4J，
 * 所以这一份实现两端共用，不必各写一遍。
 */
public final class Slf4jDgLogger implements DgLogger {

    private final Logger logger;

    public Slf4jDgLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    /** SLF4J 没有 "severe"，映射到 error —— 这个级别只用于"AI 已停用"之类的严重情况。 */
    @Override
    public void severe(String message) {
        logger.error(message);
    }
}
