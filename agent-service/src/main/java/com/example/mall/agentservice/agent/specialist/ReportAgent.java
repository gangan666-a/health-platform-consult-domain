package com.example.mall.agentservice.agent.specialist;

import com.example.mall.agentservice.agent.AbstractSubAgent;
import com.example.mall.agentservice.config.AgentChatClientFactory;
import com.example.mall.agentservice.orchestration.AgentSession;
import com.example.mall.agentservice.orchestration.tools.ReportTools;
import com.example.mall.agentservice.rag.KnowledgeRetrievalService;
import com.example.mall.agentservice.report.PromptGuard;
import com.example.mall.agentservice.service.HealthMemoryService;
import com.example.mall.common.model.AgentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 报告解读 Agent：检验/检查报告客观中立解读。systemPrompt 强化（严谨/免责/隐私），
 * handle 注入健康档案长期记忆 + 报告工具（历史对比）。
 */
@Component
public class ReportAgent extends AbstractSubAgent {

    private final HealthMemoryService healthMemory;
    private final ReportTools reportTools;

    public ReportAgent(AgentChatClientFactory clientFactory,
                       KnowledgeRetrievalService retrievalService,
                       HealthMemoryService healthMemory,
                       ReportTools reportTools,
                       @Value("${spring.ai.openai.api-key:}") String apiKey) {
        super(clientFactory, retrievalService, apiKey);
        this.healthMemory = healthMemory;
        this.reportTools = reportTools;
    }

    @Override
    public AgentType type() {
        return AgentType.REPORT;
    }

    @Override
    public List<AgentType> handoffTargets() {
        return List.of(AgentType.CONSULTATION, AgentType.MEDICINE_QA, AgentType.SHOPPING_GUIDE, AgentType.SCIENCE);
    }

    @Override
    protected String systemPrompt() {
        return """
                你是医疗健康平台的「报告解读 Agent」。
                职责：帮助用户理解检验/检查报告中的指标含义、异常情况、是否需要复诊，并可对比同一指标的历史结果。
                原则：
                1. 解读保持客观中立、严谨，区分「检查事实」「可能原因」「建议行动」三类信息。
                2. 仅凭报告不得确诊或制定治疗方案；异常项应建议线下就医，必要时 handoff 到问诊 Agent 或问药 Agent。
                3. 回复必须带免责声明：AI 解读仅供参考，不能替代专业医疗诊断。
                4. 保护用户隐私：不提及姓名/证件号/医院等个人信息，只基于检查数据作答。
                5. 对无法判定的指标标注「需人工核对」，不猜测默认值。
                6. 异常判定（偏高/偏低/无法判定）与危急值扫描已由系统规则引擎完成：你基于输入中的标记解读，
                   不要自行拿数值比对参考范围，不得推翻或弱化系统判定结果。
                7. 输入中标注「危急值提醒/重点提醒」的内容必须原样传达，并明确建议立即就医——这是硬性要求。
                8. 检查所见（超声/放射/心电结论）按报告原文转述含义，不对影像本身下诊断。
                9. """ + PromptGuard.dataOnlyClause();
    }

    @Override
    protected Object tools(AgentSession session) {
        return reportTools.bind(session.userId());
    }

    @Override
    protected String memoryContext(AgentSession session) {
        if (session.userId() == null) {
            return "";
        }
        return healthMemory.getProfile(session.userId());
    }
}
