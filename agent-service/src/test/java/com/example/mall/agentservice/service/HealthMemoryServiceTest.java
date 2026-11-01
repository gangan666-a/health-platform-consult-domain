package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.HealthProfile;
import com.example.mall.agentservice.repository.HealthProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康记忆：红线事实（过敏/用药/慢病）写入与注入段。
 * 验证 HealthFactCard 预留的 ALLERGY/CHRONIC_FLAG/MEDICATION 三类事实真正可用，
 * 且问诊/问药链路可通过 redlineFactsSection 读取。
 */
class HealthMemoryServiceTest {

    private final HealthProfileRepository repo = mock(HealthProfileRepository.class);
    private final HealthMemoryService service = new HealthMemoryService(repo, new ObjectMapper());

    @Test
    void recordFact_persistsRedlineFactsAndFirstSeenWins() {
        HealthProfile profile = new HealthProfile();
        when(repo.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.recordFact(1L, HealthFactCard.FactType.ALLERGY, "青霉素", null);
        service.recordFact(1L, HealthFactCard.FactType.ALLERGY, "青霉素", null); // 重复，只去重
        service.recordFact(1L, HealthFactCard.FactType.MEDICATION, "二甲双胍", null);
        service.recordFact(1L, HealthFactCard.FactType.CHRONIC_FLAG, "高血压", null);

        List<HealthFactCard.FactEntry> facts = service.getFacts(1L);
        long allergy = facts.stream().filter(f -> f.type() == HealthFactCard.FactType.ALLERGY).count();
        assertEquals(1, allergy, "同一过敏重复记录应只保留一条（首次为准）");

        String section = service.redlineFactsSection(1L);
        assertTrue(section.contains("已知过敏：青霉素"));
        assertTrue(section.contains("长期用药：二甲双胍"));
        assertTrue(section.contains("慢病标签：高血压"));
    }

    @Test
    void redlineFactsSection_emptyWhenNoRedlineFacts() {
        HealthProfile profile = new HealthProfile();
        when(repo.findByUserId(2L)).thenReturn(Optional.of(profile));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        // 只写异常指标（非红线类），红线事实段应为空
        service.recordFact(2L, HealthFactCard.FactType.ABNORMAL_INDICATOR, "LDL", "5.8");
        assertEquals("", service.redlineFactsSection(2L), "无过敏/用药/慢病时不应注入红线事实段");
    }

    @Test
    void opinionFact_latestWins_subjectiveSectionRenders() {
        HealthProfile profile = new HealthProfile();
        when(repo.findByUserId(3L)).thenReturn(Optional.of(profile));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.recordFact(3L, HealthFactCard.FactType.OPINION, "剂型偏好", "怕苦，不想吃片剂");
        service.recordFact(3L, HealthFactCard.FactType.OPINION, "剂型偏好", "倾向外用药");

        var facts = service.getFacts(3L).stream()
                .filter(f -> f.type() == HealthFactCard.FactType.OPINION).toList();
        assertEquals(1, facts.size(), "同主题观点最新覆盖");
        assertEquals("倾向外用药", facts.get(0).value());

        String section = service.subjectiveSection(3L);
        assertTrue(section.contains("主观表达"));
        assertTrue(section.contains("剂型偏好：倾向外用药"));
    }

    @Test
    void subjectiveSection_emptyWhenNoOpinions() {
        HealthProfile profile = new HealthProfile();
        when(repo.findByUserId(4L)).thenReturn(Optional.of(profile));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.recordFact(4L, HealthFactCard.FactType.ALLERGY, "青霉素", null);
        assertEquals("", service.subjectiveSection(4L), "只有红线事实时不应注入观点段");
    }
}
