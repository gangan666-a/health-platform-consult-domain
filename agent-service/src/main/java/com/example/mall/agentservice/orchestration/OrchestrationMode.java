package com.example.mall.agentservice.orchestration;

/**
 * 编排模式：Supervisor（Manager 委派）与 Handoff（Sub Agent 直连切换）相结合。
 */
public enum OrchestrationMode {

    /** Manager 作为 Supervisor，通过 Tool 委派 Sub Agent */
    SUPERVISOR,

    /** 会话已 handoff 到某 Sub Agent，由该 Agent 续聊并可再次 handoff */
    HANDOFF
}
