package com.example.mall.agentservice.report;

/**
 * 单页内容：PDF 文本层页（text 非空、image 为 null）或图片页（照片/扫描页，image 非空）。
 * 文本层页走文本 LLM 通道；图片页走视觉通道（未接入真实 VLM 时明确标记未识别，不虚构数据）。
 */
public record PageContent(int pageNo, String text, byte[] image) {

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean isVisionOnly() {
        return !hasText() && image != null && image.length > 0;
    }

    /** 供 LLM 输入的截断文本（控制 token 体积）。 */
    public String textForPrompt(int maxChars) {
        if (!hasText()) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
