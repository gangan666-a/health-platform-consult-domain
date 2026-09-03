package com.example.mall.agentservice.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.example.mall.agentservice.config.EmbeddingClient;
import com.example.mall.agentservice.graph.MedicalKnowledgeSeeds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识种子灌库（演示级，幂等）：启动时向 health_content / lab_exam 灌入少量种子文档，
 * 验证 hybrid 检索链路（有 embedding key 时同时写向量，无则仅 BM25）。
 * 生产数据应由医学运营团队灌入，本 Runner 可用 es.knowledge.seed-enabled=false 关闭。
 */
@Component
public class KnowledgeSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeedRunner.class);

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 本批种子知识的生效日（valid_from）：与知识版本配套，用于按时间维度回溯「当时依据哪一版知识」。 */
    private static final String KNOWLEDGE_VALID_FROM = "2026-09-01";

    /** 待灌文档：index + doc_id + agent_type + 文本字段 + 向量文本（title + 摘要）。 */
    private record SeedDoc(KnowledgeIndex index, String docId, Map<String, Object> source, String embedText) {
    }

    private final ElasticsearchClient client;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final boolean seedEnabled;

    public KnowledgeSeedRunner(ElasticsearchClient client, EmbeddingClient embeddingClient,
                               ObjectMapper objectMapper,
                               org.springframework.core.env.Environment environment) {
        this.client = client;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.seedEnabled = environment.getProperty("es.knowledge.seed-enabled", Boolean.class, true);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("知识种子灌库已关闭（es.knowledge.seed-enabled=false）");
            return;
        }
        try {
            int seeded = seedAll();
            log.info("知识种子灌库完成：本次新灌 {} 篇（embedding {}）", seeded, embeddingClient.enabled() ? "启用" : "未启用");
        } catch (Exception e) {
            log.warn("知识种子灌库失败（ES 未启动或 IK 插件未装?）: {}", e.getMessage());
        }
    }

    private int seedAll() throws Exception {
        List<SeedDoc> docs = buildSeeds();
        List<float[]> vectors = embeddingClient.enabled()
                ? embeddingClient.embedAll(docs.stream().map(SeedDoc::embedText).toList())
                : null;
        int seeded = 0;
        for (int i = 0; i < docs.size(); i++) {
            SeedDoc doc = docs.get(i);
            if (upToDate(doc)) {
                continue;
            }
            Map<String, Object> source = new HashMap<>(doc.source());
            source.put("doc_id", doc.docId());
            source.put("status", "ACTIVE");
            source.put("updated_at", LocalDateTime.now().format(FORMAT));
            if (vectors != null) {
                source.put("embedding", objectMapper.convertValue(vectors.get(i), List.class));
            }
            client.index(IndexRequest.of(b -> b
                    .index(doc.index().alias())
                    .id(doc.docId())
                    .document(source)));
            seeded++;
        }
        return seeded;
    }

    /**
     * 幂等判定：文档不存在 → 需灌；文档存在但知识版本落后 → 需重灌（P0-2）。
     * <p>
     * 版本刷新的必要性：存量 relation 文档写于出处字段引入之前，若只按 doc_id 判存在，
     * 它们将永远停留在无出处的旧版，v1 降级链路会一直回退成 UNKNOWN/C。
     * 未声明 version 的文档（health/lab/template）不参与版本刷新，沿用按 doc_id 去重。
     */
    private boolean upToDate(SeedDoc doc) {
        try {
            var resp = client.search(s -> s
                    .index(doc.index().alias())
                    .query(q -> q.term(t -> t.field("doc_id").value(doc.docId())))
                    .size(1), Map.class);
            if (resp.hits() == null || resp.hits().hits() == null || resp.hits().hits().isEmpty()) {
                return false;
            }
            Map<String, Object> existing = resp.hits().hits().get(0).source();
            if (existing == null) {
                return false;
            }
            Object expectedVersion = doc.source().get("version");
            if (expectedVersion == null) {
                return true; // 该文档不声明知识版本 → 不参与版本刷新
            }
            return expectedVersion.equals(existing.get("version"));
        } catch (Exception e) {
            return false; // 查询失败按不存在处理（后续 index 失败会进总 catch）
        }
    }

    private static List<SeedDoc> buildSeeds() {
        List<SeedDoc> docs = new ArrayList<>();

        // ---- health_content：科普/问诊/用药常识（覆盖各 Agent 的 agent_type） ----
        docs.add(health("hc-lipid", "SCIENCE", "高血脂科普：血脂四项怎么看",
                "血脂四项包括总胆固醇、甘油三酯、高密度脂蛋白胆固醇、低密度脂蛋白胆固醇。低密度脂蛋白胆固醇（LDL-C）俗称坏胆固醇，升高与动脉粥样硬化关系最密切。血脂异常早期常无症状，建议通过定期体检发现。控制血脂以低脂饮食、规律运动为基础，必要时在医生指导下用药。"));
        docs.add(health("hc-anemia", "SCIENCE", "贫血科普：血红蛋白低意味着什么",
                "血红蛋白（HGB）低于参考范围提示贫血，常见原因包括缺铁性贫血、慢性病性贫血等。轻度贫血可表现为乏力、头晕、面色苍白。缺铁性贫血可通过增加红肉、动物肝脏摄入改善，中重度贫血应就医查明病因，不建议自行补铁。"));
        docs.add(health("hc-uric", "SCIENCE", "尿酸高与痛风的关系",
                "尿酸（UA）升高称为高尿酸血症，长期升高可诱发痛风性关节炎。尿酸高者应限制高嘌呤饮食（动物内脏、海鲜、啤酒），多饮水。无痛风发作的高尿酸血症以生活方式干预为主，是否用药需医生评估。"));
        docs.add(health("hc-thyroid", "SCIENCE", "甲状腺结节科普",
                "甲状腺结节非常常见，绝大多数为良性。超声报告中的 TI-RADS 分级用于评估恶性风险，3 类以下定期随访即可，4 类以上建议专科就诊评估。发现结节不必恐慌，应结合超声特征与医生意见处理。"));
        docs.add(health("hc-fatty-liver", "SCIENCE", "脂肪肝是怎么回事",
                "脂肪肝指肝细胞内脂肪堆积，常见于超重、饮酒、血脂异常人群。多数脂肪肝可通过减重、限酒、运动逆转。转氨酶轻度升高伴脂肪肝时，建议肝病科随诊评估。"));
        docs.add(health("hc-dizzy", "CONSULTATION", "头晕的常见原因与就诊建议",
                "头晕常见原因包括血压异常、贫血、耳石症、颈椎问题、睡眠不足等。反复头晕建议监测血压、检查血常规，若伴视物旋转、言语不清、肢体无力等神经系统症状应立即就医。"));
        docs.add(health("hc-fever", "CONSULTATION", "发烧咳嗽什么情况需要就医",
                "普通感冒多可自愈。若高热超过三天不退、呼吸困难、胸痛、意识改变，或婴幼儿与老年人出现精神差、进食少，应及时就医。自行用药不超过说明书剂量，避免多种感冒药叠加服用。"));
        docs.add(health("hc-ibuprofen", "MEDICINE_QA", "布洛芬与对乙酰氨基酚的用药常识",
                "布洛芬与对乙酰氨基酚是常用解热镇痛药。两者一般不建议同时服用，确需交替使用应咨询医生或药师。布洛芬应餐后服用，胃溃疡患者慎用；对乙酰氨基酚每日总量不超过说明书上限，肝功能不全者慎用。"));
        docs.add(health("hc-otc", "SHOPPING_GUIDE", "感冒药选购常识",
                "选购感冒药前先看症状：发热头痛选含对乙酰氨基酚的复方感冒药；鼻塞流涕选含伪麻黄碱成分；干咳选右美沙芬。注意复方感冒药成分叠加风险，同时服用两种感冒药前应咨询药师。处方药（Rx）须凭处方购买。"));

        // ---- lab_exam：检验知识（REPORT） ----
        docs.add(lab("le-cbc", "血常规",
                List.of(ind("WBC", "3.5-9.5", "10^9/L", "白细胞升高常见于感染、应激；降低可见于病毒感染或血液系统疾病", "白细胞降低需警惕感染风险与血液系统疾病"),
                        ind("RBC", "3.8-5.8", "10^12/L", "红细胞升高可见于脱水、真性红细胞增多症", "红细胞降低提示贫血"),
                        ind("HGB", "115-150", "g/L", "血红蛋白升高可见于脱水、吸烟", "血红蛋白降低提示贫血，重度需就医")),
                "血常规异常建议结合症状判断：感染相关指标明显异常或重度贫血建议就医复查。"));
        docs.add(lab("le-liver", "肝功能",
                List.of(ind("ALT", "7-40", "U/L", "ALT 升高提示肝细胞损伤，常见原因有脂肪肝、饮酒、药物性肝损伤", "ALT 降低临床意义有限"),
                        ind("AST", "13-35", "U/L", "AST 升高可见于肝损伤、心肌损伤", "AST 降低临床意义有限"),
                        ind("GGT", "10-60", "U/L", "GGT 升高常见于饮酒、胆道疾病", "GGT 降低临床意义有限")),
                "转氨酶超过参考上限 2 倍以上建议肝病科就诊；超过 10 倍属危急情况应立即就医。"));
        docs.add(lab("le-lipid", "血脂四项",
                List.of(ind("TC", "<5.2", "mmol/L", "总胆固醇升高为动脉粥样硬化危险因素", "总胆固醇降低可见于营养不良"),
                        ind("TG", "<1.7", "mmol/L", "甘油三酯升高与饮食、饮酒关系大，明显升高有胰腺炎风险", "甘油三酯降低临床意义有限"),
                        ind("LDL", "<3.4", "mmol/L", "低密度脂蛋白胆固醇（坏胆固醇）升高为核心危险因素", "LDL 降低临床意义有限"),
                        ind("HDL", ">1.0", "mmol/L", "HDL 升高一般认为无害", "高密度脂蛋白（好胆固醇）降低增加心血管风险")),
                "血脂异常以生活方式干预为基础，心血管高危人群的降脂目标需个体化，建议心内科评估。"));
        docs.add(lab("le-glucose", "血糖",
                List.of(ind("GLU", "3.9-6.1", "mmol/L", "空腹血糖升高提示糖尿病风险，需复查与糖耐量试验", "空腹血糖降低可见于饥饿、降糖药过量，严重低血糖需紧急处理"),
                        ind("HBA1C", "4.0-6.0", "%", "糖化血红蛋白反映近 3 个月平均血糖", "糖化血红蛋白降低提示平均血糖偏低")),
                "空腹血糖受损与糖尿病诊断需多次检测确认，确诊者应内分泌科随诊。"));
        docs.add(lab("le-renal", "肾功能",
                List.of(ind("CRE", "57-97", "umol/L", "肌酐升高提示肾功能受损，明显升高需肾内科就诊", "肌酐降低临床意义有限"),
                        ind("UA", "208-428", "umol/L", "尿酸升高可诱发痛风", "尿酸降低临床意义有限"),
                        ind("BUN", "3.6-9.5", "mmol/L", "尿素氮升高可见于脱水、肾功能受损", "尿素氮降低可见于营养不良")),
                "肾功能指标异常建议复查并评估尿常规，持续异常应肾内科就诊。"));

        // ---- report_template：报告模板（REPORT） ----
        docs.add(template("rt-physical", "健康体检报告", "健康体检汇总报告，含总检结论与各科室检查结果", "封面→总检结论→检验明细→超声/放射→心电图"));
        docs.add(template("rt-lab", "化验单", "检验科报告，含指标表格与参考范围", "检验项目表格：项目/结果/单位/参考范围"));

        // ---- knowledge_relation：指标-疾病关系边（v1 查表层，与 Neo4j 种子同源） ----
        // 支持边：relation=INDICATES_{HIGH|LOW}，polarity=SUPPORT（v1 查表只取这一类）
        for (MedicalKnowledgeSeeds.SeedEdge edge : MedicalKnowledgeSeeds.EDGES) {
            docs.add(relation(edge));
        }
        // 反向边：polarity=CONTRAINDICATE。v1 关系查表不消费（这是 v2 才有的能力），
        // 落库仅为与图同源、供后续 v1 侧演进与人工审核核对。
        for (MedicalKnowledgeSeeds.CounterEdge edge : MedicalKnowledgeSeeds.COUNTER_EDGES) {
            docs.add(counterRelation(edge));
        }

        return docs;
    }

    /** 支持边文档：带出处/证据等级/适用人群/条件说明/知识版本（P0-2）。 */
    private static SeedDoc relation(MedicalKnowledgeSeeds.SeedEdge edge) {
        Map<String, Object> source = new HashMap<>();
        source.put("from_type", "INDICATOR");
        source.put("from_id", edge.indicator());
        source.put("to_type", "DISEASE");
        source.put("to_id", edge.disease());
        source.put("relation", "INDICATES_" + edge.direction());
        source.put("polarity", "SUPPORT");
        source.put("weight", 1.0);
        source.put("description", edge.indicator() + ("HIGH".equals(edge.direction()) ? "偏高" : "偏低")
                + "与" + edge.disease() + "相关（就诊：" + edge.department() + "）");
        source.put("source", edge.source());
        source.put("evidence_grade", edge.evidenceGrade());
        source.put("population", edge.population());
        source.put("caveat", edge.caveat());
        source.put("version", edge.version());
        source.put("valid_from", KNOWLEDGE_VALID_FROM);
        source.put("agent_type", "REPORT");
        return new SeedDoc(KnowledgeIndex.KNOWLEDGE_RELATION, "kr-" + edge.indicator() + "-" + edge.direction()
                + "-" + edge.disease(), source, edge.indicator() + " " + edge.disease());
    }

    /** 反向证据边文档（P0-3）：与图侧 CONTRAINDICATES 同源。 */
    private static SeedDoc counterRelation(MedicalKnowledgeSeeds.CounterEdge edge) {
        Map<String, Object> source = new HashMap<>();
        source.put("from_type", "INDICATOR");
        source.put("from_id", edge.indicator());
        source.put("to_type", "DISEASE");
        source.put("to_id", edge.disease());
        source.put("relation", "CONTRAINDICATES");
        source.put("polarity", "CONTRAINDICATE");
        source.put("weight", 1.0);
        source.put("description", edge.indicator() + edge.direction() + " 削弱" + edge.disease()
                + "：" + edge.reason());
        source.put("source", MedicalKnowledgeSeeds.SRC_DEMO);
        source.put("evidence_grade", "C");
        source.put("population", "成人");
        source.put("caveat", edge.reason());
        source.put("version", MedicalKnowledgeSeeds.KNOWLEDGE_VERSION);
        source.put("valid_from", KNOWLEDGE_VALID_FROM);
        source.put("agent_type", "REPORT");
        return new SeedDoc(KnowledgeIndex.KNOWLEDGE_RELATION, "kr-c-" + edge.indicator() + "-"
                + edge.direction() + "-" + edge.disease(), source,
                edge.indicator() + " " + edge.disease());
    }

    private static Map<String, Object> ind(String name, String ref, String unit, String high, String low) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("ref_range", ref);
        m.put("unit", unit);
        m.put("high_meaning", high);
        m.put("low_meaning", low);
        return m;
    }

    private static SeedDoc health(String docId, String agentType, String title, String content) {
        Map<String, Object> source = new HashMap<>();
        source.put("title", title);
        source.put("content", content);
        source.put("agent_type", agentType);
        return new SeedDoc(KnowledgeIndex.HEALTH_CONTENT, docId, source, title + "\n" + content);
    }

    private static SeedDoc lab(String docId, String examName, List<Map<String, Object>> indicators,
                               String advice) {
        Map<String, Object> source = new HashMap<>();
        source.put("exam_name", examName);
        source.put("indicators", indicators);
        source.put("abnormal_advice", advice);
        source.put("agent_type", "REPORT");
        String embedText = examName + "\n" + advice + "\n" + indicators.stream()
                .map(i -> i.get("name") + " " + i.get("high_meaning") + " " + i.get("low_meaning"))
                .reduce("", (a, b) -> a + b + "\n");
        return new SeedDoc(KnowledgeIndex.LAB_EXAM, docId, source, embedText);
    }

    private static SeedDoc template(String docId, String name, String description, String layout) {
        Map<String, Object> source = new HashMap<>();
        source.put("template_name", name);
        source.put("description", description);
        source.put("layout", layout);
        source.put("agent_type", "REPORT");
        return new SeedDoc(KnowledgeIndex.REPORT_TEMPLATE, docId, source, name + "\n" + description);
    }
}
