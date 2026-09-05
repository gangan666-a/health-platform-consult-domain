package com.example.mall.agentservice.orchestration;

import com.example.mall.common.model.AgentType;

/**
 * 无 LLM 时的启发式路由（框架占位，保证可运行）。
 * 命中超意图（平台能力/运营类诉求，不属于任何 Sub Agent 领域）返回 MANAGER，由 Manager 直接兜底作答。
 */
final class ManagerRouter {

    private ManagerRouter() {
    }

    static AgentType heuristicRoute(String message) {
        String m = message.toLowerCase();
        // 超意图兜底：平台能力咨询/运营类诉求交给 Manager 直接作答，不强行塞给专业 Agent
        if (containsAny(m, "你能做什么", "你们能做什么", "平台是干嘛", "都能干什么", "怎么使用",
                "投诉", "退款", "人工客服", "客服电话")) {
            return AgentType.MANAGER;
        }
        if (containsAny(m, "报告", "化验", "血常规", "转氨酶", "指标")) {
            return AgentType.REPORT;
        }
        if (containsAny(m, "买什么", "推荐", "购药", "下单", "药店")) {
            return AgentType.SHOPPING_GUIDE;
        }
        if (containsAny(m, "怎么吃", "用法", "用量", "副作用", "相互作用", "药")) {
            return AgentType.MEDICINE_QA;
        }
        if (containsAny(m, "科普", "预防", "是什么病", "原理")) {
            return AgentType.SCIENCE;
        }
        if (containsAny(m, "症状", "难受", "疼", "发烧", "咳嗽", "问诊", "医生")) {
            return AgentType.CONSULTATION;
        }
        return AgentType.CONSULTATION;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
