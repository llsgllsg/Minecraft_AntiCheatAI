package com.gfish.anticheat.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeepGuard 的 NeoForge 服务端入口（对应 Paper 版的 {@code AntiCheatPlugin}、
 * Fabric 版的 {@code DeepGuardFabric}）。
 * <p>
 * 目前只是骨架：先确认 26.2 这套工具链（ModDevGradle + 非混淆 MC + Java 25）
 * 能正常出 jar，检测逻辑在后续阶段接入 —— 与 Fabric 版当前进度一致。
 * <p>
 * {@code dist = Dist.DEDICATED_SERVER} 表示这个 mod 只在专用服务端加载，
 * 客户端不需要安装。
 */
@Mod(value = DeepGuardNeoForge.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class DeepGuardNeoForge {

    public static final String MOD_ID = "deepguard";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public DeepGuardNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("DeepGuard (NeoForge) 已加载");
    }
}
