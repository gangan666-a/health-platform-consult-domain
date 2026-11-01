package com.example.mall.agentservice.orchestration;

import com.example.mall.agentservice.agent.SubAgent;
import com.example.mall.agentservice.agent.specialist.ConsultationAgent;
import com.example.mall.agentservice.agent.specialist.MedicineQaAgent;
import com.example.mall.agentservice.agent.specialist.ReportAgent;
import com.example.mall.agentservice.agent.specialist.ScienceAgent;
import com.example.mall.agentservice.agent.specialist.ShoppingGuideAgent;
import com.example.mall.common.model.AgentType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * handoff 拓扑门禁（CI 必跑）：五个专家 Agent 全互连——任意一方可交接给其余四方；
 * SAFETY / MANAGER 不得出现在白名单（SAFETY 是每条消息的前置 fail-closed 审查，
 * MANAGER 走 handoffToManager 专用工具）。拓扑调整后此门禁保证「全互连、无安全旁路」契约不变。
 * 构造参数传 null：handoffTargets() 是纯拓扑声明，不依赖任何协作对象。
 */
class HandoffGraphTest {

    @Test
    void specialists_areFullyConnected_withoutSafetyBypass() {
        Map<AgentType, SubAgent> agents = new EnumMap<>(AgentType.class);
        agents.put(AgentType.CONSULTATION, new ConsultationAgent(null, null, null, null, null, ""));
        agents.put(AgentType.MEDICINE_QA, new MedicineQaAgent(null, null, null, null, null, ""));
        agents.put(AgentType.SHOPPING_GUIDE, new ShoppingGuideAgent(null, null, ""));
        agents.put(AgentType.SCIENCE, new ScienceAgent(null, null, ""));
        agents.put(AgentType.REPORT, new ReportAgent(null, null, null, null, null, ""));

        for (SubAgent agent : agents.values()) {
            List<AgentType> targets = agent.handoffTargets();

            Set<AgentType> expected = new HashSet<>(agents.keySet());
            expected.remove(agent.type());
            assertEquals(expected, new HashSet<>(targets),
                    agent.type() + " 的 handoff 目标应为其余四个专家 Agent（全互连）");

            assertFalse(targets.contains(AgentType.SAFETY),
                    "SAFETY 是前置 fail-closed 审查，不得进入 handoff 白名单");
            assertFalse(targets.contains(AgentType.MANAGER),
                    "MANAGER 走 handoffToManager 专用工具，不进白名单");
        }
    }
}
