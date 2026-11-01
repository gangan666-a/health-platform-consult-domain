package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.HealthProfile;
import com.example.mall.agentservice.repository.HealthProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * L3 人物画像（长期记忆的人物维度）：称呼 / 沟通风格 / 健康目标 / 主观偏好，
 * JSON 存 health_profile.persona_card，与 L1 事实卡（fact_card）同表不同列。
 * 写入：问诊/问药 Agent 经 ProfileMemoryTools 在用户明确表达时合并更新（增量、幂等）；
 * 读取：personaSection 注入 system prompt（全空返回空串，基类仅在非空时追加）。
 */
@Service
public class UserPersonaService {

    private static final Logger log = LoggerFactory.getLogger(UserPersonaService.class);

    private final HealthProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    public UserPersonaService(HealthProfileRepository profileRepository, ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        ObjectMapper configured = objectMapper.copy();
        configured.findAndRegisterModules();
        this.objectMapper = configured;
    }

    @Transactional
    public void upsertPersona(Long userId, UserPersonaCard patch) {
        if (userId == null || patch == null || patch.isEmpty()) {
            return;
        }
        HealthProfile profile = profileRepository.findByUserId(userId).orElseGet(HealthProfile::new);
        UserPersonaCard card = readCard(profile.getPersonaCard());
        card.merge(patch, LocalDate.now());
        if (profile.getUserId() == null) {
            profile.setUserId(userId);
        }
        profile.setPersonaCard(writeCard(card));
        profile.setUpdatedAt(LocalDate.now().atStartOfDay());
        profileRepository.save(profile);
    }

    /** 注入段：全空返回空串（与红线/观点段一致，仅在非空时由调用方追加）。 */
    public String personaSection(Long userId) {
        return profileRepository.findByUserId(userId)
                .map(p -> readCard(p.getPersonaCard()).deriveSection())
                .orElse("");
    }

    public UserPersonaCard getPersonaCard(Long userId) {
        return profileRepository.findByUserId(userId)
                .map(p -> readCard(p.getPersonaCard()))
                .orElseGet(UserPersonaCard::new);
    }

    private UserPersonaCard readCard(String json) {
        if (json == null || json.isBlank()) {
            return new UserPersonaCard();
        }
        try {
            return objectMapper.readValue(json, UserPersonaCard.class);
        } catch (Exception e) {
            log.warn("人物画像卡反序列化失败，按空卡重建（不阻断）: {}", e.getMessage());
            return new UserPersonaCard();
        }
    }

    private String writeCard(UserPersonaCard card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            throw new IllegalStateException("人物画像卡序列化失败", e);
        }
    }
}
