package com.example.mall.agentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L1 事实卡合并语义：同指标更新保留 firstSeen、红线类事实不被容量裁剪、摘要可派生重建。 */
class HealthFactCardTest {

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);

    private static HealthFactCard.FactEntry abnormal(String name, String value, String direction, LocalDate date) {
        return new HealthFactCard.FactEntry(HealthFactCard.FactType.ABNORMAL_INDICATOR,
                name, value, direction, date, date, date);
    }

    @Test
    void mergeSameIndicator_updatesValue_keepsFirstSeen() {
        HealthFactCard card = new HealthFactCard()
                .setFacts(new ArrayList<>(List.of(abnormal("LDL", "4.5", "HIGH", D1))));

        card.merge(List.of(abnormal("LDL", "5.2", "HIGH", D2)), TODAY);

        assertEquals(1, card.facts().size());
        HealthFactCard.FactEntry entry = card.facts().get(0);
        assertEquals("5.2", entry.value(), "最新值滚动更新");
        assertEquals(D1, entry.firstSeen(), "首次发现保留（趋势起点）");
        assertEquals(D2, entry.lastSeen());
    }

    @Test
    void protectedFacts_surviveAbnormalCap() {
        // 35 条异常指标（超过裁剪上限 30）+ 1 条过敏史：过敏必须存活
        List<HealthFactCard.FactEntry> initial = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            initial.add(abnormal("IND" + i, "1.0", "HIGH", D1));
        }
        initial.add(new HealthFactCard.FactEntry(HealthFactCard.FactType.ALLERGY,
                "青霉素", "皮疹", null, D1, null, D1));

        HealthFactCard card = new HealthFactCard().setFacts(new ArrayList<>(initial));
        card.merge(List.of(), TODAY);

        assertTrue(card.facts().stream().anyMatch(f -> f.type() == HealthFactCard.FactType.ALLERGY
                        && "青霉素".equals(f.name())),
                "容量裁剪只作用于异常指标，过敏史等红线类事实全保留");
        assertEquals(30, card.facts().stream()
                .filter(f -> f.type() == HealthFactCard.FactType.ABNORMAL_INDICATOR).count());
    }

    @Test
    void mergeRepeatedFacts_idempotent() {
        List<HealthFactCard.FactEntry> facts = List.of(abnormal("GLU", "6.5", "HIGH", D1));

        HealthFactCard once = new HealthFactCard().merge(new ArrayList<>(facts), TODAY);
        HealthFactCard twice = new HealthFactCard()
                .setFacts(new ArrayList<>(once.facts()))
                .merge(new ArrayList<>(facts), TODAY);

        assertEquals(once.facts().size(), twice.facts().size(), "同事实重放不产生重复");
    }

    @Test
    void deriveSummary_containsFacts() {
        HealthFactCard card = new HealthFactCard().setFacts(new ArrayList<>(List.of(
                abnormal("LDL", "4.5", "HIGH", D1),
                new HealthFactCard.FactEntry(HealthFactCard.FactType.CRITICAL_HISTORY,
                        "GLU-CRIT-HIGH", "GLU", null, D2, D2, D2))));

        String summary = card.deriveSummary();

        assertTrue(summary.contains("危急值史"));
        assertTrue(summary.contains("LDL"));
        assertTrue(summary.contains("关注指标"));
    }

    @Test
    void jsonRoundTrip_preservesFacts() throws Exception {
        HealthFactCard card = new HealthFactCard().setFacts(new ArrayList<>(List.of(
                abnormal("LDL", "4.5", "HIGH", D1),
                new HealthFactCard.FactEntry(HealthFactCard.FactType.ALLERGY,
                        "青霉素", null, null, D1, null, D1))));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(card);
        HealthFactCard back = mapper.readValue(json, HealthFactCard.class);
        assertEquals(2, back.facts().size(), "JSON 往返必须保留事实，实际: " + json);
        assertTrue(back.facts().stream().anyMatch(f -> f.type() == HealthFactCard.FactType.ALLERGY));
    }

    private static HealthFactCard.FactEntry opinion(String topic, String content, LocalDate date) {
        return new HealthFactCard.FactEntry(HealthFactCard.FactType.OPINION,
                topic, content, null, date, date, date);
    }

    @Test
    void mergeOpinion_sameTopicLatestWins_keepsFirstSeen() {
        HealthFactCard card = new HealthFactCard()
                .setFacts(new ArrayList<>(List.of(opinion("剂型偏好", "怕苦，不想吃片剂", D1))));

        card.merge(List.of(opinion("剂型偏好", "倾向外用药", D2)), TODAY);

        assertEquals(1, card.facts().size(), "同主题观点只保留最新一条");
        HealthFactCard.FactEntry entry = card.facts().get(0);
        assertEquals("倾向外用药", entry.value(), "观点可变，最新表达覆盖");
        assertEquals(D1, entry.firstSeen(), "首次表达时间保留");
    }

    @Test
    void mergeOpinion_differentTopics_coexist() {
        HealthFactCard card = new HealthFactCard();
        card.merge(List.of(
                opinion("剂型偏好", "倾向外用药", D1),
                opinion("治疗态度", "倾向食疗优先", D2)), TODAY);

        assertEquals(2, card.facts().size(), "不同主题的观点各自保留");
    }

    @Test
    void opinionCap_prunesOldestBeyondTen() {
        List<HealthFactCard.FactEntry> initial = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            initial.add(opinion("主题" + i, "内容" + i, LocalDate.of(2026, 8, i)));
        }
        HealthFactCard card = new HealthFactCard().setFacts(new ArrayList<>(initial));

        card.merge(List.of(), TODAY);

        assertEquals(10, card.facts().size(), "观点只保最近 10 条");
        assertTrue(card.facts().stream().noneMatch(f -> "主题1".equals(f.name())), "最旧观点被裁剪");
        assertTrue(card.facts().stream().anyMatch(f -> "主题11".equals(f.name())), "最新观点保留");
    }

    @Test
    void deriveSummary_containsOpinions() {
        HealthFactCard card = new HealthFactCard()
                .setFacts(new ArrayList<>(List.of(opinion("剂型偏好", "倾向外用药", D1))));

        String summary = card.deriveSummary();

        assertTrue(summary.contains("近期观点"));
        assertTrue(summary.contains("剂型偏好"));
    }
}
