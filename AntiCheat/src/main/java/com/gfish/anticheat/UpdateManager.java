package com.gfish.anticheat;

import org.bukkit.entity.Player;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 版本检测 + 模型自动下载。
 * <p>
 * - 启动时异步查询 GitHub 最新 Release，发现新版本时通知在线管理员。
 * - 启动时自动下载最新模型到数据目录并重载，管理员无需手动替换模型文件。
 * - 配置见 config.yml 的 {@code updates} 段；可用 /ac update 手动触发。
 */
public final class UpdateManager {

    private static final String DEFAULT_VERSION_URL =
            "https://api.github.com/repos/llsgllsg/Minecraft_AntiCheatAI/releases/latest";
    private static final String DEFAULT_MODEL_URL =
            "https://github.com/llsgllsg/Minecraft_AntiCheatAI/releases/latest/download/scaffold_detector.onnx";

    private final AntiCheatPlugin plugin;
    private final HttpClient http;

    public UpdateManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** updates 总开关。 */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("updates.enabled", true);
    }

    public boolean versionCheckEnabled() {
        return plugin.getConfig().getBoolean("updates.version-check", true);
    }

    public boolean modelAutoDownloadEnabled() {
        return plugin.getConfig().getBoolean("updates.auto-download-model", true);
    }

    private String config(String key, String def) {
        String v = plugin.getConfig().getString(key, "");
        return (v == null || v.isBlank()) ? def : v;
    }

    /** 异步检查最新版本，若存在新版本则通知在线管理员。 */
    public void checkVersionAsync() {
        if (!isEnabled() || !versionCheckEnabled()) return;
        String url = config("updates.version-url", DEFAULT_VERSION_URL);

        CompletableFuture.supplyAsync(() -> fetchLatestTag(url))
                .thenAccept(latest -> {
                    if (latest == null) return;
                    String current = plugin.getDescription().getVersion();
                    if (isNewer(latest, current)) {
                        plugin.getLogger().warning("发现新版本 " + latest
                                + " (当前 " + current + ")，请前往 GitHub 查看更新。");
                        for (Player p : plugin.getServer().getOnlinePlayers()) {
                            if (p.hasPermission("deepguard.admin")) {
                                p.sendMessage("§e[DeepGuard] 检测到新版本 §f" + latest
                                        + "§e，当前 " + current + "。");
                            }
                        }
                    } else {
                        plugin.getLogger().info("已是最新版本 (" + current + ")。");
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("版本检查失败: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * 异步下载最新模型到数据目录，完成后由插件重载。
     * 仅在 auto-download-model 开启时执行。
     */
    public CompletableFuture<Void> downloadModelAsync() {
        if (!isEnabled() || !modelAutoDownloadEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        String url = config("updates.model-url", DEFAULT_MODEL_URL);
        String modelPath = plugin.getConfig().getString("ai.model-path", "scaffold_detector.onnx");

        return CompletableFuture.supplyAsync(() -> {
            try {
                File model = new File(plugin.getDataFolder(), modelPath);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(model.toPath()));
                if (resp.statusCode() / 100 != 2) {
                    Files.deleteIfExists(model.toPath());
                    throw new IllegalStateException("HTTP " + resp.statusCode());
                }
                plugin.getLogger().info("AI 模型自动下载成功 (" + (model.length() / 1024) + " KB)");
                return model;
            } catch (Exception e) {
                throw new IllegalStateException("AI 模型下载失败: " + e.getMessage(), e);
            }
        }).thenRun(() -> {
            if (plugin.isEnabled()) {
                plugin.loadAiModel();
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning(ex.getMessage());
            return null;
        });
    }

    /** 从 GitHub API 响应中解析最新 tag 名（无 JSON 依赖的轻量解析）。 */
    private String fetchLatestTag(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DeepGuard")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            int idx = body.indexOf("\"tag_name\":\"");
            if (idx == -1) return null;
            int start = idx + "\"tag_name\":\"".length();
            int end = body.indexOf('"', start);
            return end == -1 ? null : body.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isNewer(String candidate, String current) {
        int[] a = parseVersion(candidate);
        int[] b = parseVersion(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] > b[i]) return true;
            if (a[i] < b[i]) return false;
        }
        return false;
    }

    private int[] parseVersion(String v) {
        int[] out = new int[3];
        String s = v.replaceAll("[^0-9.]", "");
        String[] parts = s.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
