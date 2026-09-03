package com.example.mall.agentservice.report;

import java.time.LocalDate;
import java.util.List;

/**
 * 文件画像（PROFILE 步骤产物）：确定性前置工具（PDFBox 分页 / EXIF 归一化）的输出，
 * 供规划器决策每页解析通道。exifCaptureDate 为照片件拍摄日期（兜底报告日期，隐私红线不读 GPS）。
 */
public record FileProfile(String fileName, String contentType, boolean pdf,
                          List<PageContent> pages, LocalDate exifCaptureDate) {

    public boolean hasAnyTextPage() {
        return pages.stream().anyMatch(PageContent::hasText);
    }
}
