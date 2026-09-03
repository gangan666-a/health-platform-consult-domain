package com.example.mall.agentservice.controller;

import com.example.mall.agentservice.orchestration.AgentOrchestrator;
import com.example.mall.common.model.AgentChatRequest;
import com.example.mall.common.model.AgentChatResponse;
import com.example.mall.common.model.AgentType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentOrchestrator orchestrator;

    public AgentController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request) {
        return orchestrator.chat(request);
    }

    @GetMapping("/registry")
    public Map<String, Object> registry() {
        List<Map<String, String>> agents = Arrays.stream(AgentType.values())
                .map(t -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("type", t.name());
                    m.put("displayName", t.displayName());
                    m.put("description", t.description());
                    return m;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orchestration", "supervisor+handoff");
        body.put("agents", agents);
        return body;
    }
}
