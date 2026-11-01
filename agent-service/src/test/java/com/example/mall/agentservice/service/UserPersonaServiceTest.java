package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.HealthProfile;
import com.example.mall.agentservice.repository.HealthProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L3 人物画像服务：增量合并落库 + 注入段渲染（空档案返回空串）。 */
class UserPersonaServiceTest {

    private final HealthProfileRepository repo = mock(HealthProfileRepository.class);
    private final UserPersonaService service = new UserPersonaService(repo, new ObjectMapper());

    @Test
    void upsertPersona_mergesIntoExistingCard() throws Exception {
        HealthProfile profile = new HealthProfile();
        profile.setUserId(1L);
        profile.setPersonaCard("""
                {"nickname":"老张","healthGoals":["控糖"],"updatedAt":"2026-08-01"}
                """);
        when(repo.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserPersonaCard patch = new UserPersonaCard();
        patch.setCommStyle("简洁要点式");
        patch.setHealthGoals(java.util.List.of("减重"));
        service.upsertPersona(1L, patch);

        ArgumentCaptor<HealthProfile> captor = ArgumentCaptor.forClass(HealthProfile.class);
        verify(repo).save(captor.capture());
        UserPersonaCard saved = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .readValue(captor.getValue().getPersonaCard(), UserPersonaCard.class);
        assertEquals("老张", saved.getNickname(), "未修改字段保留");
        assertEquals("简洁要点式", saved.getCommStyle());
        assertEquals(java.util.List.of("控糖", "减重"), saved.getHealthGoals(), "目标去重追加");
    }

    @Test
    void personaSection_blankWhenNoProfile() {
        when(repo.findByUserId(1L)).thenReturn(Optional.empty());

        assertEquals("", service.personaSection(1L));
    }

    @Test
    void personaSection_rendersCollectedCard() {
        HealthProfile profile = new HealthProfile();
        profile.setUserId(1L);
        profile.setPersonaCard("""
                {"nickname":"老张","commStyle":"简洁要点式","preferences":["怕苦"],"updatedAt":"2026-08-01"}
                """);
        when(repo.findByUserId(1L)).thenReturn(Optional.of(profile));

        String section = service.personaSection(1L);

        assertTrue(section.contains("称呼：老张"));
        assertTrue(section.contains("沟通风格：简洁要点式"));
        assertTrue(section.contains("怕苦"));
    }
}
