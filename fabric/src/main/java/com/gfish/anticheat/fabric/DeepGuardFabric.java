package com.gfish.anticheat.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeepGuard 的 Fabric 服务端入口（对应 Paper 版的 {@code AntiCheatPlugin}）。
 * <p>
 * 目前只是骨架：先确认 26.2 这套工具链（非混淆 Loom + Java 25 + Fabric API）
 * 能正常出 jar，检测逻辑在后续阶段接入。
 */
public final class DeepGuardFabric implements ModInitializer {

    public static final String MOD_ID = "deepguard";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("DeepGuard (Fabric) 已加载");
    }
}
