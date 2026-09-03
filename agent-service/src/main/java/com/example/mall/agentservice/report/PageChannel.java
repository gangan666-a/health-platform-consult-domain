package com.example.mall.agentservice.report;

/** 页解析通道：文本层页走文本 LLM，图片/扫描页走视觉通道。 */
public enum PageChannel {

    TEXT_LLM("文本LLM"),
    VISION("视觉通道");

    private final String display;

    PageChannel(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /** LLM 输出词 → 通道；不可解析返回 null（调用方按文本层有无兜底）。 */
    public static PageChannel parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim().toUpperCase().split("[\\s,，。;；]+")[0];
        for (PageChannel channel : values()) {
            if (channel.name().equals(normalized)) {
                return channel;
            }
        }
        return null;
    }
}
