package com.example.mall.agentservice.storage;

import java.util.Optional;

/**
 * 报告原文存储端口：上传时留档（供用户回看原报告），删除报告时连带清理。
 * 实现两级：阿里云 OSS（生产，agent.oss.enabled=true）/ 本地目录（演示与降级）。
 * 隐私：键不含 PII（userId + 文件 hash）；存储不可用时降级不留档（报告解读不受影响）。
 */
public interface ReportFileStorage {

    record StoredFile(String key, String storageType) {
    }

    /** 存储原始文件，返回存储键与类型；失败抛出（由调用方降级为不留档）。 */
    StoredFile store(Long userId, String fileHash, byte[] content, String fileName, String contentType);

    /** 读回文件字节（本地实现流式语义；OSS 实现拉取对象）。 */
    byte[] load(String key);

    /** 删除文件（被遗忘权：报告删除时连带清理）。 */
    void delete(String key);

    /** HEIC/HEIF → JPEG 转码（仅 OSS 图片处理可实现）；不支持或失败返回空，由调用方 fail-closed 拒绝。 */
    Optional<byte[]> transcodeHeic(byte[] content);

    String type();
}
