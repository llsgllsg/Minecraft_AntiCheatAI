package com.gfish.anticheat.core.spi;

import java.util.concurrent.CompletableFuture;

/**
 * 版本检查 / 模型自动下载 SPI。
 * <p>
 * 内核只负责把 {@code /ac update} 这个命令转出去；"去哪儿查、怎么下、下完怎么重载"
 * 各平台自己实现（Paper 走 GitHub Release + 解压到数据目录，模组端还要负责
 * 重建 ONNX 子类加载器）。
 * <p>
 * 平台不支持时返回 null，内核会给出一句可读的提示而不是 NPE。
 */
public interface UpdateService {

    /** 异步检查新版本；发现新版本时由实现自己通知在线管理员。 */
    CompletableFuture<Void> checkVersionAsync();

    /** 异步下载最新模型并在完成后重载。 */
    CompletableFuture<Void> downloadModelAsync();
}
