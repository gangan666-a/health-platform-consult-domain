package com.example.mall.agentservice.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.example.mall.agentservice.report.FormatSniffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 阿里云 OSS 存储（生产实现）：agent.oss.enabled=true 且配置 AK/bucket 时启用。
 * 设计要点（面试讲点）：
 * ① 键规则 {userId}/{hash}.{ext}——无 PII、天然幂等（同文件同键覆盖写）；
 * ② 服务端加密（SSE-OSS）+ 桶策略禁公读，预签名 URL 限时访问（默认 30 分钟）；
 * ③ 生命周期由桶规则管理（如 180 天自动沉降/删除），应用侧删除走同步 deleteObject（被遗忘权）；
 * ④ 未配置 AK 时应用启动不受影响——本 Bean 不装配，自动落到 LocalReportFileStorage。
 */
@Component
@ConditionalOnProperty(name = "agent.oss.enabled", havingValue = "true")
public class OssReportFileStorage implements ReportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(OssReportFileStorage.class);

    private static final long PRESIGN_MINUTES = 30;

    private final OSS ossClient;
    private final String bucket;

    public OssReportFileStorage(@Value("${agent.oss.endpoint}") String endpoint,
                                @Value("${agent.oss.access-key-id}") String accessKeyId,
                                @Value("${agent.oss.access-key-secret}") String accessKeySecret,
                                @Value("${agent.oss.bucket}") String bucket) {
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        this.bucket = bucket;
        log.info("报告原文存储启用 OSS：bucket={} endpoint={}", bucket, endpoint);
    }

    @Override
    public StoredFile store(Long userId, String fileHash, byte[] content, String fileName, String contentType) {
        String key = objectKey(userId, fileHash, fileName);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType == null ? "application/octet-stream" : contentType);
        metadata.setContentLength(content.length);
        // 服务端加密：桶侧静态加密（SSE-OSS），密钥由 KMS 托管，应用不接触
        metadata.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        ossClient.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(content), metadata));
        return new StoredFile(key, type());
    }

    @Override
    public byte[] load(String key) {
        try {
            Path temp = Files.createTempFile("report-download-", ".tmp");
            ossClient.getObject(new GetObjectRequest(bucket, key), temp.toFile());
            byte[] content = Files.readAllBytes(temp);
            Files.deleteIfExists(temp);
            return content;
        } catch (Exception e) {
            throw new IllegalStateException("OSS 读取失败: " + key + " (" + e.getMessage() + ")", e);
        }
    }

    /** 预签名 URL（限时直连 OSS 下载，应用不做流量中转）。 */
    public String presignedUrl(String key) {
        Date expiration = new Date(System.currentTimeMillis() + PRESIGN_MINUTES * 60_000L);
        URL url = ossClient.generatePresignedUrl(bucket, key, expiration);
        return url.toString();
    }

    /**
     * HEIC/HEIF → JPEG 转码：OSS ImageMagick（x-oss-process=image/format,jpeg）服务端转码，
     * 不引入任何 Java HEIC 解码库。实现为一次性临时管道：落临时对象 → 转码拉回 → 校验 JPEG 魔数（fail-closed，
     * 产物非 JPEG 不放行）→ 清理临时对象，不留档（原文留档由 store() 另存原始字节）。
     */
    @Override
    public Optional<byte[]> transcodeHeic(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        String key = "transcode-heic/" + UUID.randomUUID().toString().replace("-", "") + ".heic";
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType("image/heic");
            metadata.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
            ossClient.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(content), metadata));

            GetObjectRequest request = new GetObjectRequest(bucket, key);
            request.setProcess("image/format,jpeg");
            Path temp = Files.createTempFile("report-transcode-", ".jpg");
            try {
                ossClient.getObject(request, temp.toFile());
                byte[] converted = Files.readAllBytes(temp);
                if (FormatSniffer.isJpeg(FormatSniffer.detect(converted))) {
                    return Optional.of(converted);
                }
                log.warn("OSS 转码产物非 JPEG 魔数，fail-closed 拒绝: key={}", key);
                return Optional.empty();
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            log.warn("OSS HEIC 转码失败，fail-closed 拒绝: {}", e.getMessage());
            return Optional.empty();
        } finally {
            try {
                ossClient.deleteObject(bucket, key);
            } catch (Exception ignored) {
                log.debug("临时转码对象清理失败（无害）: key={}", key);
            }
        }
    }

    @Override
    public void delete(String key) {
        ossClient.deleteObject(bucket, key);
    }

    @Override
    public String type() {
        return "oss";
    }

    private static String objectKey(Long userId, String fileHash, String fileName) {
        String ext = "";
        if (fileName != null) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            int idx = lower.lastIndexOf('.');
            if (idx >= 0) {
                ext = lower.substring(idx);
            }
        }
        return userId + "/" + fileHash + ext;
    }
}
