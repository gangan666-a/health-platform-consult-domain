package com.example.mall.agentservice.orchestration.tools;

import com.example.mall.agentservice.agent.AgentInvocationContext;
import com.example.mall.agentservice.agent.SubAgent;
import com.example.mall.agentservice.agent.SubAgentRegistry;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.agentservice.orchestration.OrchestrationMode;
import com.example.mall.common.model.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Manager Supervisor 的委派工具：每个可路由的 Sub Agent 暴露为一个 @Tool。
 * 安全 Agent 不在委派清单——它是所有消息的前置 fail-closed 审查，不是路由参与者。
 * 委派异常不外抛：以【委派失败】标记返回给 Manager 供其反思改派；Sub Agent 成功后才激活会话。
 */
public class ManagerDelegateTools {

    private static final Logger log = LoggerFactory.getLogger(ManagerDelegateTools.class);

    private final SubAgentRegistry registry;
    private final AgentSession session;
    private final AgentInvocationContext context;
    /** Manager 整理的会话要点（组织上下文），随每次委派下发给 Sub Agent */
    private final String contextBrief;

    public ManagerDelegateTools(SubAgentRegistry registry, AgentSession session,
                                AgentInvocationContext context, String contextBrief) {
        this.registry = registry;
        this.session = session;
        this.context = context;
        this.contextBrief = contextBrief;
    }

    @Tool(description = "委派给问诊 Agent：症状分析、就医建议、在线问诊引导")
    public String consultationAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.CONSULTATION, query);
    }

    @Tool(description = "委派给问药 Agent：用法用量、禁忌、相互作用")
    public String medicineQaAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.MEDICINE_QA, query);
    }

    @Tool(description = "委派给导购 Agent：选药推荐与购药路径")
    public String shoppingGuideAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.SHOPPING_GUIDE, query);
    }

    @Tool(description = "委派给科普 Agent：疾病与健康知识科普")
    public String scienceAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.SCIENCE, query);
    }

    @Tool(description = "委派给报告解读 Agent：检验检查报告解读")
    public String reportAgent(@ToolParam(description = "用户问题原文") String query) {
        return delegate(AgentType.REPORT, query);
    }

    private String delegate(AgentType type, String query) {
        SubAgent agent = registry.require(type);
        // 组织上下文：把 Manager 整理的会话要点随委派注入，接手方不丢上下文
        context.setDelegationNote("委派目标：" + type.displayName() + "。会话要点：" + contextBrief);
        try {
            String reply = agent.handle(session, query, context);
            if (reply == null || reply.isBlank()) {
                // 反思信号：空答复以失败标记返回，Manager LLM 可据此改派或兜底
                return "【委派失败】" + type.displayName() + "未返回有效内容";
            }
            session.activate(type, OrchestrationMode.HANDOFF);
            return reply;
        } catch (Exception e) {
            log.warn("Manager 委派 {} 失败", type, e);
            return "【委派失败】" + type.displayName() + "暂时不可用，可改派其他 Agent 或直接答复用户";
        }
    }
}
