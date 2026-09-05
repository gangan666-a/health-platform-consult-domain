package com.example.mall.agentservice.agent;

import com.example.mall.common.model.AgentType;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次 Agent 调用的上下文：handoff 意图、RAG 片段、trace。
 */
public class AgentInvocationContext {

    private boolean handoffRequested;
    private AgentType handoffFrom;
    private AgentType handoffTarget;
    private String handoffReason;
    private final List<AgentType> trace = new ArrayList<>();
    private String ragContext = "";
    /** Manager 委派时附带的会话要点（组织上下文），随委派注入 Sub Agent 本轮 system prompt */
    private String delegationNote = "";
    /** handoff 交接纪要（上一 Agent 会话历史的总结），注入接手 Agent 本轮 system prompt */
    private String handoffNote = "";

    public void addTrace(AgentType agent) {
        trace.add(agent);
    }

    public List<AgentType> trace() {
        return trace;
    }

    public void requestHandoff(AgentType from, AgentType target, String reason) {
        this.handoffRequested = true;
        this.handoffFrom = from;
        this.handoffTarget = target;
        this.handoffReason = reason;
    }

    public AgentType handoffFrom() {
        return handoffFrom;
    }

    public boolean isHandoffRequested() {
        return handoffRequested;
    }

    public AgentType handoffTarget() {
        return handoffTarget;
    }

    public String handoffReason() {
        return handoffReason;
    }

    public String ragContext() {
        return ragContext;
    }

    public void setRagContext(String ragContext) {
        this.ragContext = ragContext != null ? ragContext : "";
    }

    public String delegationNote() {
        return delegationNote;
    }

    public void setDelegationNote(String delegationNote) {
        this.delegationNote = delegationNote != null ? delegationNote : "";
    }

    public String handoffNote() {
        return handoffNote;
    }

    public void setHandoffNote(String handoffNote) {
        this.handoffNote = handoffNote != null ? handoffNote : "";
    }
}
