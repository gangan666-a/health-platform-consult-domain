package com.example.mall.agentservice.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentSessionStore {

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    public AgentSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        final String key = sessionId;
        return sessions.computeIfAbsent(key, AgentSession::new);
    }

    public void handoff(AgentSession session, com.example.mall.common.model.AgentType from,
                        com.example.mall.common.model.AgentType to, String reason) {
        session.recordHandoff(from, to, reason);
    }

    public void resetToManager(AgentSession session) {
        session.activate(com.example.mall.common.model.AgentType.MANAGER, OrchestrationMode.SUPERVISOR);
    }
}
