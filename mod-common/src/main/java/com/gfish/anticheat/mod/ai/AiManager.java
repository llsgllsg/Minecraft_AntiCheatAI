package com.gfish.anticheat.mod.ai;

import com.gfish.anticheat.core.DeepGuardCore;
import com.gfish.anticheat.core.config.DeepGuardConfig;
import com.gfish.anticheat.core.platform.PlayerHandle;
import com.gfish.anticheat.core.spi.AiEngine;
import com.gfish.anticheat.core.spi.UpdateService;
import com.gfish.anticheat.mod.McPlatform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模组端的 AI 生命周期：释放内置模型、建 ONNX 引擎、自动更新模型。
 * <p>
 * 与 Paper 版的差别只在"引擎从哪来"：Paper 用 {@code plugin.yml} 的
 * {@code libraries:} 让服务端自己把 onnxruntime 挂上类路径，模组只能用
 * {@link OnnxRuntimeLoader} 运行时下载 + 子类加载器隔离。
 * 模型本身的取用逻辑（内置解压 → 自动下载最新）与 Paper 一致。
 */
public final class AiManager {

    /** 内置模型在模组资源里的位置，构建期从 Paper 模块拷过来。 */
    private static final String MODEL_RESOURCE = "/deepguard/scaffold_detector.onnx";
    /** 模型约 170 KB；低于 1 KB 一定是下到了错误页。 */
    private static final long MIN_MODEL_BYTES = 1024;

    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    private final McPlatform platform;
    private final DeepGuardCore core;
    private final String modVersion;
    private final Path workDir;
    private final HttpDownloader downloader = new HttpDownloader();
    private final OnnxRuntimeLoader runtimeLoader;

    private volatile AiEngine engine;

    public AiManager(McPlatform platform, DeepGuardCore core, String modVersion) {
        this.platform = platform;
        this.core = core;
        this.modVersion = modVersion;
        this.workDir = platform.dataFolder().resolve("onnx");
        this.runtimeLoader = new OnnxRuntimeLoader(workDir, platform.logger());
    }

    /** 模型文件路径（由配置的 {@code ai.model-path} 决定）。 */
    public Path modelFile() {
        return platform.dataFolder().resolve(core.config().modelPath);
    }

    /**
     * 启动时调用。<b>不阻塞主线程</b>：首次运行要下 87 MB，绝不能卡在启动路径上。
     */
    public void initAsync() {
        if (!core.config().aiEnabled) {
            platform.logger().info("配置里 ai.enabled=false，AI 检测未启用（移动类检查照常工作）。");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                ensureModelFile();
                install(runtimeLoader.createEngine(modelFile()));
                platform.logger().info("AI 检测已就绪。");
            } catch (IOException e) {
                platform.logger().warn("AI 初始化失败，本次运行只启用移动类检查: " + e.getMessage());
                return;
            }
            if (core.config().autoDownloadModelEnabled) {
                downloadModelAsync();
            }
        }).exceptionally(ex -> {
            platform.logger().warn("AI 初始化异常: " + ex.getMessage());
            return null;
        });
    }

    /** 组装给内核用的更新服务（{@code /ac update} 会调它）。 */
    public UpdateService updateService() {
        return new UpdateService() {
            @Override
            public CompletableFuture<Void> checkVersionAsync() {
                return AiManager.this.checkVersionAsync();
            }

            @Override
            public CompletableFuture<Void> downloadModelAsync() {
                return AiManager.this.downloadModelAsync();
            }
        };
    }

    // ------------------------------------------------------------------
    // 模型文件
    // ------------------------------------------------------------------

    /** 模型缺失时从模组资源释放一份，保证开箱即用。 */
    private void ensureModelFile() throws IOException {
        Path model = modelFile();
        if (Files.exists(model)) {
            return;
        }
        Path parent = model.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream in = AiManager.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) {
                throw new IOException("模组资源里找不到 " + MODEL_RESOURCE);
            }
            Files.copy(in, model);
        }
        platform.logger().info("已释放内置模型: " + model);
    }

    /**
     * 下载最新模型并在成功后热替换引擎。
     * <p>
     * 失败时<b>保留现有引擎</b>：模型下载是锦上添花，不该把已经在跑的 AI 弄没。
     */
    public CompletableFuture<Void> downloadModelAsync() {
        DeepGuardConfig config = core.config();
        if (!config.updatesEnabled || !config.autoDownloadModelEnabled) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                Path model = modelFile();
                downloader.download(URI.create(config.modelUrl), model, MIN_MODEL_BYTES);
                platform.logger().info("AI 模型自动下载成功 (" + (Files.size(model) / 1024) + " KB)");
                install(runtimeLoader.createEngine(model));
            } catch (Exception e) {
                platform.logger().warn("AI 模型更新失败（继续使用现有模型）: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> checkVersionAsync() {
        DeepGuardConfig config = core.config();
        if (!config.updatesEnabled || !config.versionCheckEnabled) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            String body = downloader.getString(URI.create(config.versionUrl));
            if (body == null) {
                return;
            }
            Matcher matcher = TAG_NAME.matcher(body);
            if (!matcher.find()) {
                return;
            }
            String latest = matcher.group(1);
            if (!isNewer(latest, modVersion)) {
                platform.logger().info("已是最新版本 (" + modVersion + ")。");
                return;
            }
            platform.logger().warn("发现新版本 " + latest + " (当前 " + modVersion + ")，请前往 GitHub 查看更新。");
            platform.runOnMainThread(() -> {
                for (PlayerHandle p : platform.onlinePlayers()) {
                    if (p.hasPermission("deepguard.admin")) {
                        p.sendMessage("§e[DeepGuard] 检测到新版本 §f" + latest
                                + "§e，当前 " + modVersion + "。");
                    }
                }
            });
        }).exceptionally(ex -> {
            platform.logger().warn("版本检查失败: " + ex.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------------
    // 引擎安装
    // ------------------------------------------------------------------

    /**
     * 在主线程上换引擎（内核的 installAiEngine 会读配置并打日志，
     * 顺手也避免了异步线程直接改内核状态）。
     */
    private void install(AiEngine created) {
        platform.runOnMainThread(() -> {
            AiEngine previous = engine;
            if (core.installAiEngine(created)) {
                engine = created;
                if (previous != null) {
                    closeQuietly(previous);
                }
            } else {
                closeQuietly(created);
            }
        });
    }

    /**
     * 关掉旧引擎释放 native 会话。
     * <p>
     * {@code close()} 不在 {@link AiEngine} SPI 上（SPI 要能被父加载器看见，
     * 而生命周期方法只有实现方需要），所以走反射。
     */
    private static void closeQuietly(AiEngine engine) {
        try {
            engine.getClass().getMethod("close").invoke(engine);
        } catch (ReflectiveOperationException ignored) {
            // 关不掉只能算了，泄漏一个会话好过打断模型热更新
        }
    }

    // ------------------------------------------------------------------
    // 版本比较（与 Paper 版 UpdateManager 同一套规则）
    // ------------------------------------------------------------------

    private static boolean isNewer(String candidate, String current) {
        int[] a = parseVersion(candidate);
        int[] b = parseVersion(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] > b[i]) return true;
            if (a[i] < b[i]) return false;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[3];
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                // 非数字段按 0 处理，与 Paper 版一致
            }
        }
        return out;
    }
}
