package com.example.mall.agentservice.es;

/**
 * 医疗知识库逻辑索引清单（10 个）。统一走「版本化 backing index + 别名」模式，
 * 应用只读写别名（health-&lt;name&gt;），换 mapping / 换 embedding 模型时 reindex 零停机。
 */
public enum KnowledgeIndex {

    DRUG("drug"),
    SYMPTOM("symptom"),
    DISEASE("disease"),
    DEPARTMENT("department"),
    LAB_EXAM("lab_exam"),
    HEALTH_CONTENT("health_content"),
    DRUG_INTERACTION("drug_interaction"),
    FAQ_COMPLIANCE("faq_compliance"),
    KNOWLEDGE_RELATION("knowledge_relation"),
    REPORT_TEMPLATE("report_template");

    private final String name;

    KnowledgeIndex(String name) {
        this.name = name;
    }

    /** 逻辑名（如 drug） */
    public String logicalName() {
        return name;
    }

    /** 读写别名（如 health-drug） */
    public String alias() {
        return "health-" + name;
    }

    /** 版本化 backing index 名（如 health-drug-000001） */
    public String backingIndex(int version) {
        return "health-" + name + "-" + String.format("%06d", version);
    }

    /** 当前版本的 backing index 名（版本从 1 起） */
    public String currentBackingIndex() {
        return backingIndex(1);
    }

    /** mapping 定义资源路径（classpath:es/&lt;name&gt;.mapping.json） */
    public String mappingResource() {
        return "classpath:es/" + name + ".mapping.json";
    }

    @Override
    public String toString() {
        return logicalName();
    }
}
