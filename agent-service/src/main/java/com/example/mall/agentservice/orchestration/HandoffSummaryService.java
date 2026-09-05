package com.example.mall.agentservice.orchestration;

import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.config.AgentLlmSupport;
import com.example.mall.common.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 交接纪要服务：Sub-Agent 间 handoff 时，把上一 Agent 的会话历史总结成简短纪要随单交接——
 * 接手方拿到的是「总结后的上下文」：既不传整段对话原文（避免长 prompt 拖慢推理），
 * 也不用让用户向新 Agent 复述一遍病情。
 * LLM 可用时走 LLM 总结；不可用或失败时降级为近期对话要点的确定性摘要，永不阻断交接。
 */
@Component
public class HandoffSummaryService {

    private static final Logger log = LoggerFactory.getLogger(HandoffSummaryService.class);

    /** 纪要最多回看的消息条数与总字符预算（控制注入 token） */
    private static final int MAX_DIGEST_MESSAGES = 6;
    private static final int MAX_DIGEST_CHARS = 400;

    private final ChatMemory chatMemory;
    private final AgentChatClientFactory clientFactory;
    private final boolean llmEnabled;

    public HandoffSummaryService(ChatMemory chatMemory, AgentChatClientFactory clientFactory,
                                 @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.chatMemory = chatMemory;
        this.clientFactory = clientFactory;
        this.llmEnabled = AgentLlmSupport.isLlmEnabled(apiKey);
    }

    /**
     * 生成随 handoff 交接的纪要（带标头与交接原因）。任何异常都降级为占位纪要，不抛出。
     */
    public String summarize(String sessionId, AgentType from, String handoffReason) {
        String reason = handoffReason == null || handoffReason.isBlank() ? "未填写" : handoffReason.trim();
        String header = "【交接纪要 · 来自" + from.displayName() + "】";
        try {
            List<Message> history = chatMemory.get(AgentChatClientFactory.memoryKey(sessionId, from));
            if (history == null || history.isEmpty()) {
                return header + "\n（上一环节暂无历史对话）\n交接原因：" + reason;
            }
            String body = digest(history, MAX_DIGEST_MESSAGES);
            if (llmEnabled) {
                try {
                    String summarized = summarizeWithLlm(from, body);
                    if (summarized != null && !summarized.isBlank()) {
                        body = summarized;
                    }
                } catch (Exception e) {
                    log.warn("交接纪要 LLM 总结失败，降级确定性摘要: {}", e.getMessage());
                }
            }
            return header + "\n" + body + "\n交接原因：" + reason;
        } catch (Exception e) {
            log.warn("交接纪要生成失败: {}", e.getMessage());
            return header + "\n（纪要生成失败）\n交接原因：" + reason;
        }
    }

    /**
     * 近期对话要点（确定性摘要）：Manager 委派的「组织上下文」与纪要降级共用。
     */
    public String digest(String sessionId, AgentType ofAgent, int maxMessages) {
        try {
            List<Message> history = chatMemory.get(AgentChatClientFactory.memoryKey(sessionId, ofAgent));
            return digest(history, maxMessages);
        } catch (Exception e) {
            return "（历史上下文不可用）";
        }
    }

    private String digest(List<Message> history, int maxMessages) {
        if (history == null || history.isEmpty()) {
            return "（暂无历史对话）";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : history.subList(Math.max(0, history.size() - maxMessages), history.size())) {
            String role = m.getMessageType() == MessageType.USER ? "用户" : "助手";
            sb.append(role).append("：").append(truncate(m.getText(), 120)).append('\n');
        }
        String text = sb.toString().trim();
        return text.length() > MAX_DIGEST_CHARS ? "…" + text.substring(text.length() - MAX_DIGEST_CHARS) : text;
    }

    private String summarizeWithLlm(AgentType from, String transcript) {
        ChatClient client = clientFactory.forOneShot("""
                你是医疗多 Agent 系统的交接纪要员。把对话原文压缩成不超过 120 字的要点纪要，
                覆盖：用户主诉 / 已给出的建议 / 未决问题。只输出纪要正文，不要评论或致谢。
                """);
        return client.prompt()
                .user("上一环节：" + from.displayName() + "\n对话原文：\n" + transcript)
                .call()
                .content();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
