package com.example.mall.agentservice.agent;

import com.example.mall.common.model.AgentType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SubAgentRegistry {

    private final Map<AgentType, SubAgent> agents = new EnumMap<>(AgentType.class);

    public SubAgentRegistry(List<SubAgent> subAgents) {
        for (SubAgent agent : subAgents) {
            agents.put(agent.type(), agent);
        }
    }

    public Optional<SubAgent> get(AgentType type) {
        return Optional.ofNullable(agents.get(type));
    }

    public SubAgent require(AgentType type) {
        return get(type).orElseThrow(() -> new IllegalArgumentException("未注册的 Sub Agent: " + type));
    }

    public List<SubAgent> specialists() {
        return agents.values().stream()
                .filter(a -> a.type().isSpecialist() && a.type() != AgentType.SAFETY)
                .toList();
    }
}
