package com.example.mall.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.orchestration")
public class AgentOrchestrationProperties {

    /** Supervisor 模式下单轮最多委派次数 */
    private int maxSupervisorRounds = 3;
    /** 是否启用 Sub Agent 间 handoff */
    private boolean handoffEnabled = true;

    public int getMaxSupervisorRounds() {
        return maxSupervisorRounds;
    }

    public void setMaxSupervisorRounds(int maxSupervisorRounds) {
        this.maxSupervisorRounds = maxSupervisorRounds;
    }

    public boolean isHandoffEnabled() {
        return handoffEnabled;
    }

    public void setHandoffEnabled(boolean handoffEnabled) {
        this.handoffEnabled = handoffEnabled;
    }
}
