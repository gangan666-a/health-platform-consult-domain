package com.example.mall.agentservice.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地留档实现：存取删回环 + 键规则（userId/hash 无 PII）。 */
class LocalReportFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void storeLoadDelete_roundTrip() {
        LocalReportFileStorage storage = new LocalReportFileStorage(tempDir.toString());
        byte[] content = {1, 2, 3, 4, 5};

        ReportFileStorage.StoredFile stored = storage.store(42L, "abc123", content, "xuechanggui.pdf", "application/pdf");

        assertEquals("local", stored.storageType());
        assertEquals("42/abc123.pdf", stored.key(), "键 = userId/hash.ext，不含 PII");
        assertArrayEquals(content, storage.load(stored.key()));

        storage.delete(stored.key());
        assertFalse(tempDir.resolve("42/abc123.pdf").toFile().exists());
    }

    @Test
    void typeIsLocal() {
        assertEquals("local", new LocalReportFileStorage(tempDir.toString()).type());
        assertTrue(tempDir.toFile().canRead());
    }

    @Test
    void transcodeHeic_isUnsupported_locally() {
        // 本地目录无图片转码能力（纯 Java 解 HEIC 无好方案）→ 返回空，由合规门禁定向拒绝并提示转码
        assertEquals(Optional.empty(),
                new LocalReportFileStorage(tempDir.toString()).transcodeHeic(new byte[]{1, 2, 3}));
    }
}
