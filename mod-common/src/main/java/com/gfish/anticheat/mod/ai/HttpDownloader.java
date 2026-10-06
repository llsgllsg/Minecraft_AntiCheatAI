package com.gfish.anticheat.mod.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * 极简下载器。两个模组共用：runtime 与模型都走这里。
 * <p>
 * 关键点是<b>先下到临时文件再原子替换</b>：直接写目标文件的话，一次失败
 * （例如 Release 还没发布导致的 404、或中途断网）就会把现有可用文件毁掉。
 * Paper 版的 UpdateManager 也是这个套路。
 */
public final class HttpDownloader {

    private final HttpClient http;

    public HttpDownloader() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 下载到 {@code target}。
     *
     * @param minBytes 小于这个大小视为下载失败 —— 防的是"下载到一个 HTML 错误页"
     *                 这种情况：HTTP 200 但内容根本不是 jar
     */
    public void download(URI uri, Path target, long minBytes) throws IOException, InterruptedException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = parent == null
                ? Path.of(target.getFileName() + ".download")
                : parent.resolve(target.getFileName() + ".download");

        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "DeepGuard")
                    .GET()
                    .build();
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " (" + uri + ")");
            }
            long size = Files.size(tmp);
            if (size < minBytes) {
                throw new IOException("下载内容过小（" + size + " 字节），可能不是有效文件: " + uri);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程，下次下载会覆盖
            }
            throw e;
        }
    }

    /** 取一段文本（用于 GitHub API），失败返回 null。 */
    public String getString(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DeepGuard")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return response.body();
        } catch (Exception e) {
            return null;
        }
    }
}
