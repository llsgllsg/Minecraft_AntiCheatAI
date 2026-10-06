package com.gfish.anticheat.mod.ai;

import com.gfish.anticheat.core.platform.DeepGuardPlatform;
import com.gfish.anticheat.core.platform.DgLogger;
import com.gfish.anticheat.core.spi.AiEngine;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * onnxruntime 的运行时加载：下载 jar + 用子类加载器把桥接实现拉起来。
 * <p>
 * <b>为什么要这么绕</b>：onnxruntime 有 87 MB，不可能打进模组 jar（两个平台
 * × 各平台 native 库）；而且模组的类路径在启动时就固定了，没法事后追加。
 * 所以只能运行时下载，再用一个独立的 {@link URLClassLoader} 把它和桥接实现
 * 一起加载起来。
 * <p>
 * <b>父加载器必须是 {@code DeepGuardPlatform.class.getClassLoader()}</b>，
 * 不能用 {@code Thread.currentThread().getContextClassLoader()}：后者在模组
 * 环境里指向哪个加载器完全看运气（加载器各阶段会换），一旦指错，
 * {@code AiEngine} 就会被子加载器再定义一次，父类那边转型时
 * {@code ClassCastException}。
 * <p>
 * 子加载器<b>全程只建一次</b>：onnxruntime 的 native 库加载后卸不掉，
 * 同一个 JVM 里第二次加载会抛
 * 「Native Library ... already loaded in another classloader」。
 */
public final class OnnxRuntimeLoader {

    /** 与 Paper 版 plugin.yml 的 libraries 声明保持一致。 */
    public static final String ONNXRUNTIME_VERSION = "1.17.1";

    private static final URI ONNXRUNTIME_URI = URI.create(
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/"
                    + ONNXRUNTIME_VERSION + "/onnxruntime-" + ONNXRUNTIME_VERSION + ".jar");

    private static final String BRIDGE_CLASS = "com.gfish.anticheat.bridge.OnnxAiEngine";
    /** 桥接 jar 在模组资源里的位置，构建期由 bridgeJar 任务放进去。 */
    private static final String BRIDGE_RESOURCE = "/deepguard/onnx-bridge.jar";

    /** onnxruntime jar 约 87 MB；小于 40 MB 一定是下到了错误页或半截文件。 */
    private static final long MIN_RUNTIME_BYTES = 40L * 1024 * 1024;

    private final Path workDir;
    private final DgLogger logger;
    private final HttpDownloader downloader = new HttpDownloader();

    private URLClassLoader loader;

    public OnnxRuntimeLoader(Path workDir, DgLogger logger) {
        this.workDir = workDir;
        this.logger = logger;
    }

    /** onnxruntime jar 的本地路径（下载后）。 */
    public Path runtimeJar() {
        return workDir.resolve("onnxruntime-" + ONNXRUNTIME_VERSION + ".jar");
    }

    public boolean isRuntimePresent() {
        return Files.exists(runtimeJar());
    }

    /**
     * 准备好 runtime（必要时下载），然后造一个绑定到 {@code modelFile} 的引擎。
     * <p>
     * 阻塞操作：87 MB 的下载 + native 库加载，调用方必须在后台线程上执行。
     */
    public AiEngine createEngine(Path modelFile) throws IOException {
        Path runtimeJar = runtimeJar();
        if (!Files.exists(runtimeJar)) {
            logger.info("首次运行：正在下载 ONNX 运行时（约 88 MB，仅此一次）...");
            try {
                downloader.download(ONNXRUNTIME_URI, runtimeJar, MIN_RUNTIME_BYTES);
                logger.info("ONNX 运行时下载完成: " + runtimeJar);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载 ONNX 运行时被中断", e);
            }
        }

        Path bridgeJar = extractBridgeJar();

        if (loader == null) {
            loader = new URLClassLoader(
                    new URL[]{runtimeJar.toUri().toURL(), bridgeJar.toUri().toURL()},
                    DeepGuardPlatform.class.getClassLoader());
        }

        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, true, loader);
            Object engine = bridgeClass
                    .getConstructor(String.class)
                    .newInstance(modelFile.toAbsolutePath().toString());
            return (AiEngine) engine;
        } catch (ClassNotFoundException e) {
            throw new IOException("桥接类缺失（" + BRIDGE_CLASS + "），模组 jar 可能不完整", e);
        } catch (NoClassDefFoundError e) {
            // 典型成因：父加载器抢先定义了桥接类，导致它按父加载器去解析 ai.onnxruntime
            throw new IOException("ONNX 类加载失败，检查 onnx-bridge.jar 是否被误放进主类路径", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("ONNX 引擎初始化失败: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("无法实例化 ONNX 引擎: " + e.getMessage(), e);
        }
    }

    /**
     * 把打进模组资源的 {@code onnx-bridge.jar} 释放到磁盘。
     * <p>
     * 每次都覆盖：它只有几 KB，且必须跟着模组版本走 —— 留着旧的不放，
     * 升级模组后桥接实现就还是老的。
     */
    private Path extractBridgeJar() throws IOException {
        Path target = workDir.resolve("onnx-bridge.jar");
        Files.createDirectories(workDir);
        try (InputStream in = OnnxRuntimeLoader.class.getResourceAsStream(BRIDGE_RESOURCE)) {
            if (in == null) {
                throw new IOException("模组资源里找不到 " + BRIDGE_RESOURCE + "，构建可能没跑 bridgeJar");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
