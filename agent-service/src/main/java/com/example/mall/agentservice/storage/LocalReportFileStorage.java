package com.example.mall.agentservice.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * 本地目录存储（演示/降级实现）：agent.oss.enabled=false 时启用。
 * 键规则 {userId}/{hash}.{ext}；目录由 agent.storage.local-dir 配置（默认 ./data/report-files）。
 * 不做加密与生命周期——生产用 OssReportFileStorage（服务端加密 + 生命周期规则）。
 */
@Component
@ConditionalOnProperty(name = "agent.oss.enabled", havingValue = "false", matchIfMissing = true)
public class LocalReportFileStorage implements ReportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalReportFileStorage.class);

    private final Path baseDir;

    public LocalReportFileStorage(@Value("${agent.storage.local-dir:./data/report-files}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public StoredFile store(Long userId, String fileHash, byte[] content, String fileName, String contentType) {
        try {
            Path dir = baseDir.resolve(String.valueOf(userId));
            Files.createDirectories(dir);
            Path file = dir.resolve(fileHash + extensionOf(fileName));
            Files.write(file, content);
            log.info("报告原文留档（本地）: {}", file);
            return new StoredFile(userId + "/" + fileHash + extensionOf(fileName), type());
        } catch (IOException e) {
            throw new IllegalStateException("本地留档失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(baseDir.resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("读取留档失败: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(baseDir.resolve(key));
        } catch (IOException e) {
            log.warn("删除留档失败（不阻断删除流程）: {} {}", key, e.getMessage());
        }
    }

    /** 本地目录无图片转码能力（纯 Java 解 HEIC 无好方案）→ 返回空，由调用方定向拒绝并提示转码。 */
    @Override
    public Optional<byte[]> transcodeHeic(byte[] content) {
        return Optional.empty();
    }

    @Override
    public String type() {
        return "local";
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int idx = lower.lastIndexOf('.');
        return idx >= 0 ? lower.substring(idx) : "";
    }
}
