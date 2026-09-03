package com.example.mall.common.model;

/**
 * 平台 Agent 类型：1 个 Manager 协调者 + 6 个专业 Sub Agent。
 */
public enum AgentType {

    /** 协调者：Supervisor 路由 + 多 Agent 结果汇总 */
    MANAGER("协调者", "统筹问诊、问药、购药、科普、报告解读，并调度各专业 Agent"),

    /** 问诊：症状收集、就医建议、对接 IM 问诊会话 */
    CONSULTATION("问诊 Agent", "症状分析与就医引导，必要时转 IM 在线问诊"),

    /** 问药：用药咨询、相互作用、用法用量 */
    MEDICINE_QA("问药 Agent", "药品信息、用法用量、禁忌与相互作用咨询"),

    /** 导购：选药推荐、比价、下单引导（原 recommend-service 能力迁入） */
    SHOPPING_GUIDE("导购 Agent", "根据需求推荐 OTC/Rx 药品并引导购药"),

    /** 科普：健康知识、疾病预防 */
    SCIENCE("科普 Agent", "医学科普与健康管理知识"),

    /** 报告解读：检验/检查报告结构化解读 */
    REPORT("报告解读 Agent", "化验单、影像报告等结果的通俗解读"),

    /** 安全：合规审查、敏感内容拦截、用药风险提示 */
    SAFETY("安全 Agent", "内容安全、合规与高风险用药预警");

    private final String displayName;
    private final String description;

    AgentType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** 是否为 Sub Agent（非 Manager） */
    public boolean isSpecialist() {
        return this != MANAGER;
    }
}
