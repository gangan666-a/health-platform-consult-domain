package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.HealthProfile;
import com.example.mall.agentservice.report.CriticalAlert;
import com.example.mall.agentservice.report.IndicatorStatus;
import com.example.mall.agentservice.report.ReportDoc;
import com.example.mall.agentservice.repository.HealthProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆（L1 事实卡版，PM-DESIGN·5）：报告异常指标/危急值史合并进 HealthFactCard（JSON），
 * 摘要由事实卡派生——替代旧「文本追加+截断」（截断保最新会静默丢失最旧过敏史）。
 * 注入语义：getProfile 返回派生摘要；upsert 幂等（同卡重放结果一致）。
 * ALLERGY/CHRONIC_FLAG/MEDICATION 为预留事实类型（由问诊/问药链路写入，本链路不产生）。
 */
@Service
public class HealthMemoryService {

    private static final Logger log = LoggerFactory.getLogger(HealthMemoryService.class);

    private final HealthProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    public HealthMemoryService(HealthProfileRepository profileRepository, ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        // 事实卡含 LocalDate 字段，序列化需要 JavaTimeModule。
        // 复制一份（不污染注入的共享 Bean），并自动发现 classpath 上的 JSR310 模块，
        // 无论注入的是 Spring 的 ObjectMapper 还是测试里 new 的裸 ObjectMapper 都能正确往返。
        ObjectMapper configured = objectMapper.copy();
        configured.findAndRegisterModules();
        this.objectMapper = configured;
    }

    /** 报告 → 事实条目（异常指标 + 危急值史）。 */
    public List<HealthFactCard.FactEntry> toFacts(ReportDoc doc, List<CriticalAlert> alerts) {
        List<HealthFactCard.FactEntry> entries = new ArrayList<>();
        LocalDate date = doc.reportDate() == null ? LocalDate.now() : doc.reportDate();
        for (var in : doc.indicators()) {
            if (in.status() == IndicatorStatus.HIGH || in.status() == IndicatorStatus.LOW) {
                entries.add(new HealthFactCard.FactEntry(HealthFactCard.FactType.ABNORMAL_INDICATOR,
                        in.name(), in.value(), in.status().name(), date, date, date));
            }
        }
        for (CriticalAlert alert : alerts) {
            entries.add(new HealthFactCard.FactEntry(HealthFactCard.FactType.CRITICAL_HISTORY,
                    alert.ruleId(), alert.indicatorName(), null, date, date, date));
        }
        return entries;
    }

    /** 报告级摘要（report_record.summary 快照，保持既有语义：本次检查概览）。 */
    public String summarize(ReportDoc doc) {
        long abnormal = doc.indicators().stream()
                .filter(i -> i.status() == IndicatorStatus.HIGH || i.status() == IndicatorStatus.LOW)
                .count();
        StringBuilder sb = new StringBuilder();
        sb.append(doc.reportDate()).append(' ').append(doc.examName()).append("：");
        if (abnormal == 0) {
            sb.append("各项指标均在参考范围内");
        } else {
            sb.append(abnormal).append(" 项指标异常（");
            sb.append(doc.indicators().stream()
                    .filter(i -> i.status() == IndicatorStatus.HIGH || i.status() == IndicatorStatus.LOW)
                    .map(i -> i.name() + " " + i.value() + (i.status() == IndicatorStatus.HIGH ? "↑" : "↓"))
                    .reduce((a, b) -> a + "、" + b)
                    .orElse(""));
            sb.append('）');
        }
        return sb.toString();
    }

    /** 事实卡合并 + 派生摘要落库（幂等：同事实重放结果一致）。 */
    @Transactional
    public void upsert(Long userId, List<HealthFactCard.FactEntry> newFacts) {
        HealthProfile profile = profileRepository.findByUserId(userId).orElseGet(HealthProfile::new);
        HealthFactCard card = readCard(profile.getFactCard());
        card.merge(newFacts, LocalDate.now());
        if (profile.getUserId() == null) {
            profile.setUserId(userId);
        }
        profile.setFactCard(writeCard(card));
        profile.setProfileSummary(card.deriveSummary());
        profile.setUpdatedAt(LocalDateTime.now());
        profileRepository.save(profile);
    }

    public String getProfile(Long userId) {
        return profileRepository.findByUserId(userId)
                .map(HealthProfile::getProfileSummary)
                .orElse("");
    }

    /** 读取事实卡全部事实（问诊/问药链路据此注入已知过敏/用药/慢病）。 */
    public List<HealthFactCard.FactEntry> getFacts(Long userId) {
        return profileRepository.findByUserId(userId)
                .map(p -> readCard(p.getFactCard()).facts())
                .orElse(List.of());
    }

    /**
     * 记录一条预留事实（过敏/慢病标签/用药）。首次记录为准、只去重不覆盖（merge 语义）。
     * 调用方：问诊/问药链路在用户明确陈述时写入；本方法只负责落库，不做语义抽取。
     */
    @Transactional
    public void recordFact(Long userId, HealthFactCard.FactType type, String name, String value) {
        if (userId == null || name == null || name.isBlank()) {
            return;
        }
        HealthFactCard.FactEntry entry = new HealthFactCard.FactEntry(type, name.trim(),
                value, null, LocalDate.now(), LocalDate.now(), LocalDate.now());
        upsert(userId, List.of(entry));
    }

    /**
     * 患者级红线事实段（已知过敏 / 长期用药 / 慢病标签），供问诊与问药 Agent 注入上下文。
     * 空则返回空串（基类仅在非空时追加到 system prompt）。
     */
    public String redlineFactsSection(Long userId) {
        List<HealthFactCard.FactEntry> facts = getFacts(userId).stream()
                .filter(f -> f.type() == HealthFactCard.FactType.ALLERGY
                        || f.type() == HealthFactCard.FactType.MEDICATION
                        || f.type() == HealthFactCard.FactType.CHRONIC_FLAG)
                .toList();
        if (facts.isEmpty()) {
            return "";
        }
        List<String> allergy = new ArrayList<>();
        List<String> medication = new ArrayList<>();
        List<String> chronic = new ArrayList<>();
        for (HealthFactCard.FactEntry f : facts) {
            switch (f.type()) {
                case ALLERGY -> allergy.add(f.name());
                case MEDICATION -> medication.add(f.name());
                case CHRONIC_FLAG -> chronic.add(f.name());
                default -> { /* 其它类型不进入红线段 */ }
            }
        }
        StringBuilder sb = new StringBuilder("【患者已知关键事实（问诊/问药请重点参考，避免禁忌）】");
        if (!allergy.isEmpty()) {
            sb.append("\n已知过敏：").append(String.join("、", allergy));
        }
        if (!medication.isEmpty()) {
            sb.append("\n长期用药：").append(String.join("、", medication));
        }
        if (!chronic.isEmpty()) {
            sb.append("\n慢病标签：").append(String.join("、", chronic));
        }
        return sb.toString();
    }

    private HealthFactCard readCard(String json) {
        if (json == null || json.isBlank()) {
            return new HealthFactCard();
        }
        try {
            return objectMapper.readValue(json, HealthFactCard.class);
        } catch (Exception e) {
            log.warn("事实卡反序列化失败，按空卡重建（不阻断）: {}", e.getMessage());
            return new HealthFactCard();
        }
    }

    private String writeCard(HealthFactCard card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            throw new IllegalStateException("事实卡序列化失败", e);
        }
    }
}
