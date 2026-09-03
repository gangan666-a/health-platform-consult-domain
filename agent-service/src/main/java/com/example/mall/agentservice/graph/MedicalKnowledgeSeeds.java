package com.example.mall.agentservice.graph;

import java.util.List;

/**
 * 医疗知识种子（演示级，生产由医学运营团队维护）：指标异常方向 → 疾病 → 就诊科室 → 典型症状。
 * 同一份种子双载体：① ES knowledge_relation 索引（v1 关系查表）② Neo4j 图（v2 多跳推理），
 * 保证两层语义同源，演进对比（GraphEvalTest）公平。
 *
 * <p><b>P0-2 条件化 + 出处 + 版本</b>：每条支持边必须声明
 * {@code source}（出处）、{@code evidenceGrade}（证据等级）、{@code population}（适用人群）、
 * {@code caveat}（成立条件/例外）、{@code version}（知识版本）。
 * 教程要点：出处是一等属性——没有来源与证据等级的边，图越大越危险，
 * LLM 无法区分「指南推荐」与「演示占位」，也无法在回答里给出可信度。
 *
 * <p><b>P0-3 反向与缺失证据</b>：除支持边（ABNORMAL_HIGH/LOW）外，额外声明
 * {@link #COUNTER_EDGES}（反向：削弱/排除某疾病）与 {@link #CONTEXT_REQUIREMENTS}
 * （缺失：鉴别还需补做的指标）。三路证据一起给 LLM，它才能诚实地说
 * 「更像 X，但也可能是 Y，还需要 Z 才能区分」，而不是只报支持方向。
 */
public final class MedicalKnowledgeSeeds {

    // ---- 出处常量（生产应指向具体指南条目 / 文献 DOI） ----
    public static final String SRC_LIPID_GUIDE = "中国血脂管理指南(2023)";
    public static final String SRC_DM_GUIDE = "中国2型糖尿病防治指南(2020)";
    public static final String SRC_INTERNAL_TEXTBOOK = "内科学(第9版)";
    public static final String SRC_LAB_SOP = "全国临床检验操作规程(第4版)";
    public static final String SRC_THROMBO_GUIDE = "血小板减少症诊疗专家共识";
    /** 未挂指南的占位来源：证据等级 C，输出时会显式标注「待医学审核」。 */
    public static final String SRC_DEMO = "演示种子(待医学审核)";

    /** 知识版本号：边变更时递增，用于追溯「某次解读依据的是哪一版知识」。 */
    public static final String KNOWLEDGE_VERSION = "2026.09";

    /**
     * 一条知识边：指标 + 方向 → 疾病 + 就诊科室 + 典型症状 + 出处/等级/人群/条件/版本。
     */
    public record SeedEdge(String indicator, String direction, String disease, String department,
                           List<String> symptoms, String source, String evidenceGrade,
                           String population, String caveat, String version) {

        /**
         * 兼容构造：未显式声明出处的边取默认值（来源=演示种子、证据等级 C、成人、无条件说明）。
         * 新增边请<b>优先使用全参构造</b>显式标注出处，默认值仅用于过渡期存量边。
         */
        public SeedEdge(String indicator, String direction, String disease, String department,
                        List<String> symptoms) {
            this(indicator, direction, disease, department, symptoms,
                    SRC_DEMO, "C", "成人", "", KNOWLEDGE_VERSION);
        }
    }

    /** 支持边：指标异常方向 → 疾病（含出处/证据等级/适用条件）。 */
    public static final List<SeedEdge> EDGES = List.of(
            new SeedEdge("LDL", "HIGH", "动脉粥样硬化", "心内科", List.of("头晕", "胸闷"),
                    SRC_LIPID_GUIDE, "A", "成人", "需空腹采血；正在服用他汀者按治疗目标解读", KNOWLEDGE_VERSION),
            new SeedEdge("TC", "HIGH", "动脉粥样硬化", "心内科", List.of("头晕", "胸闷"),
                    SRC_LIPID_GUIDE, "A", "成人", "受近期饮食影响较小，但仍建议复查确认", KNOWLEDGE_VERSION),
            new SeedEdge("TG", "HIGH", "动脉粥样硬化", "心内科", List.of("头晕"),
                    SRC_LIPID_GUIDE, "B", "成人", "需空腹 8-12h，餐后检测显著偏高", KNOWLEDGE_VERSION),
            new SeedEdge("GLU", "HIGH", "糖尿病", "内分泌科", List.of("多饮多尿", "乏力"),
                    SRC_DM_GUIDE, "A", "成人", "需非同日两次空腹血糖≥7.0 或 OGTT 确认，单次升高不能确诊", KNOWLEDGE_VERSION),
            new SeedEdge("HBA1C", "HIGH", "糖尿病", "内分泌科", List.of("多饮多尿", "乏力"),
                    SRC_DM_GUIDE, "A", "成人（非妊娠）", "受贫血、血红蛋白病影响；妊娠期不可用 HbA1c 诊断", KNOWLEDGE_VERSION),
            new SeedEdge("GLU", "LOW", "低血糖", "内分泌科", List.of("心悸", "出汗"),
                    SRC_DM_GUIDE, "A", "成人", "需结合症状（Whipple 三联征）；糖尿病患者低于 3.9 即按低血糖处理", KNOWLEDGE_VERSION),
            new SeedEdge("UA", "HIGH", "痛风", "风湿免疫科", List.of("关节红肿疼痛"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "高尿酸血症≠痛风；仅约 10% 高尿酸者会发作，无症状者以生活方式干预为主", KNOWLEDGE_VERSION),
            new SeedEdge("ALT", "HIGH", "脂肪肝", "消化内科", List.of("乏力", "右上腹不适"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "轻度升高（<2 倍上限）需排除饮酒、药物与近期剧烈运动", KNOWLEDGE_VERSION),
            new SeedEdge("AST", "HIGH", "脂肪肝", "消化内科", List.of("乏力"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "AST 升高亦可见于心肌与肌肉损伤，需结合 ALT 判断来源", KNOWLEDGE_VERSION),
            new SeedEdge("ALT", "HIGH", "药物性肝损伤", "消化内科", List.of("乏力", "黄疸"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "需有明确用药史与停药后恢复的时间关系", KNOWLEDGE_VERSION),
            new SeedEdge("TBIL", "HIGH", "胆汁淤积", "消化内科", List.of("黄疸", "皮肤瘙痒"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "需区分直接/间接胆红素：间接为主提示溶血或 Gilbert 综合征", KNOWLEDGE_VERSION),
            new SeedEdge("HGB", "LOW", "缺铁性贫血", "血液科", List.of("面色苍白", "头晕", "乏力"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人（非妊娠）", "妊娠期血红蛋白生理性稀释，判读阈值不同", KNOWLEDGE_VERSION),
            new SeedEdge("FE", "LOW", "缺铁性贫血", "血液科", List.of("面色苍白"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "血清铁受饮食与昼夜波动影响，铁蛋白更具诊断价值", KNOWLEDGE_VERSION),
            // FERR（铁蛋白）：P1-2 从 FE 中分离——血清铁与铁蛋白是不同 LOINC 概念（2498-4 / 2276-4），
            // 原词典把二者并入同一标准名会让「铁蛋白低但血清铁正常」的早期缺铁被漏判
            new SeedEdge("FERR", "LOW", "缺铁性贫血", "血液科", List.of("面色苍白", "乏力"),
                    SRC_INTERNAL_TEXTBOOK, "A", "成人", "铁蛋白是缺铁性贫血最敏感的指标，降低即可诊断缺铁（感染/炎症时可假性正常）", KNOWLEDGE_VERSION),
            new SeedEdge("WBC", "HIGH", "细菌感染", "感染科", List.of("发热"),
                    SRC_LAB_SOP, "B", "成人", "应激、糖皮质激素、剧烈运动亦可致升高，需结合 CRP 与症状", KNOWLEDGE_VERSION),
            new SeedEdge("CRP", "HIGH", "细菌感染", "感染科", List.of("发热"),
                    SRC_LAB_SOP, "B", "成人", "非特异性炎症指标；病毒感染亦可轻度升高", KNOWLEDGE_VERSION),
            new SeedEdge("PLT", "LOW", "血小板减少症", "血液科", List.of("出血倾向", "瘀斑"),
                    SRC_THROMBO_GUIDE, "B", "成人", "先排除采血凝集造成的假性减少（EDTA 依赖），需复检确认", KNOWLEDGE_VERSION),
            new SeedEdge("CRE", "HIGH", "慢性肾病", "肾内科", List.of("水肿", "夜尿增多"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "肌酐受肌肉量、年龄、性别影响；需结合 eGFR 与尿常规", KNOWLEDGE_VERSION),
            new SeedEdge("TSH", "HIGH", "甲状腺功能减退", "内分泌科", List.of("乏力", "怕冷"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人（非妊娠）", "TSH 升高而 FT4 正常为亚临床甲减；妊娠期目标范围不同", KNOWLEDGE_VERSION),
            new SeedEdge("TSH", "LOW", "甲状腺功能亢进", "内分泌科", List.of("心悸", "怕热多汗"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人（非妊娠）", "需结合 FT4/FT3 区分亚临床与临床甲亢", KNOWLEDGE_VERSION),
            new SeedEdge("FT4", "HIGH", "甲状腺功能亢进", "内分泌科", List.of("心悸", "怕热多汗"),
                    SRC_INTERNAL_TEXTBOOK, "B", "成人", "需排除检验干扰（生物素、甲状腺素结合球蛋白异常）", KNOWLEDGE_VERSION));

    /**
     * 反向证据（P0-3）：指标出现该方向时<b>削弱或排除</b>某疾病。
     * direction 可为 HIGH / LOW / ANY（ANY 表示不论方向，该指标异常即反向）。
     * 作用：让 LLM 能说出「不太像 X，因为 Y 与 X 方向相反」，而不是只报支持方向。
     */
    public record CounterEdge(String indicator, String direction, String disease, String reason) {
    }

    public static final List<CounterEdge> COUNTER_EDGES = List.of(
            new CounterEdge("TSH", "HIGH", "甲状腺功能亢进", "TSH 升高与甲亢方向相反，甲亢应伴 TSH 降低"),
            new CounterEdge("TSH", "LOW", "甲状腺功能减退", "TSH 降低与甲减方向相反，甲减应伴 TSH 升高"),
            new CounterEdge("FT4", "HIGH", "甲状腺功能减退", "FT4 升高与甲减方向相反"),
            new CounterEdge("GLU", "LOW", "糖尿病", "当前为低血糖状态，不支持糖尿病诊断，应排查降糖药过量或胰岛素瘤"),
            new CounterEdge("GLU", "HIGH", "低血糖", "血糖升高与低血糖方向相反"),
            new CounterEdge("PLT", "LOW", "细菌感染", "血小板减少更提示血液系统疾病或凝血消耗，非细菌感染典型表现"),
            new CounterEdge("TBIL", "HIGH", "脂肪肝", "单纯胆红素升高更提示胆汁淤积或溶血；脂肪肝典型表现为转氨酶升高"),
            new CounterEdge("CRE", "HIGH", "药物性肝损伤", "肌酐升高指向肾功能受损，与药物性肝损伤无直接关联"),
            new CounterEdge("UA", "HIGH", "慢性肾病", "尿酸升高更指向嘌呤代谢异常或痛风，非慢性肾病判定依据"));

    /**
     * 缺失上下文（P0-3）：确诊/鉴别某疾病还需补做的指标。
     * 若该指标本次报告<b>未检测</b>，则在证据包中提示「鉴别还需：X（未检测）」；
     * 已检测（无论正常与否）则不再提示——缺失指的是信息缺口，不是结果异常。
     */
    public record ContextRequirement(String disease, String indicator, String reason) {
    }

    public static final List<ContextRequirement> CONTEXT_REQUIREMENTS = List.of(
            new ContextRequirement("糖尿病", "HBA1C", "空腹血糖需糖化血红蛋白复核，单次空腹血糖不能确诊"),
            new ContextRequirement("糖尿病", "GLU", "糖化血红蛋白需空腹血糖佐证，并排除检验干扰"),
            new ContextRequirement("动脉粥样硬化", "HDL", "LDL 升高需结合 HDL 评估完整血脂谱"),
            new ContextRequirement("脂肪肝", "AST", "ALT 升高需 AST 联合判断肝损伤模式（AST/ALT 比值有意义）"),
            new ContextRequirement("脂肪肝", "GGT", "GGT 可鉴别酒精性肝损伤"),
            new ContextRequirement("药物性肝损伤", "TBIL", "胆红素升高提示肝损伤程度加重，是重症化判断依据"),
            new ContextRequirement("缺铁性贫血", "FERR", "铁蛋白是确认缺铁最敏感的指标"),
            new ContextRequirement("缺铁性贫血", "FE", "血清铁/铁蛋白可明确是否为缺铁性贫血"),
            new ContextRequirement("缺铁性贫血", "HGB", "需血红蛋白确认贫血及其程度"),
            new ContextRequirement("细菌感染", "CRP", "CRP 与白细胞联合判断感染与炎症程度"),
            new ContextRequirement("细菌感染", "WBC", "CRP 需白细胞计数区分感染类型"),
            new ContextRequirement("慢性肾病", "BUN", "尿素氮与肌酐联合评估肾功能"),
            new ContextRequirement("慢性肾病", "CRE", "肾功能判定以肌酐与 eGFR 为核心"),
            new ContextRequirement("甲状腺功能减退", "FT4", "TSH 升高需 FT4 区分亚临床与临床甲减"),
            new ContextRequirement("甲状腺功能亢进", "FT4", "TSH 降低需 FT4 区分亚临床与临床甲亢"),
            new ContextRequirement("痛风", "UA", "痛风诊断以血尿酸为核心依据"),
            new ContextRequirement("血小板减少症", "PLT", "需血小板计数确认减少程度"),
            new ContextRequirement("低血糖", "GLU", "低血糖判定以血糖实测值为准"),
            new ContextRequirement("胆汁淤积", "TBIL", "胆红素是胆汁淤积的核心判定指标"));

    private MedicalKnowledgeSeeds() {
    }
}
