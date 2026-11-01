package com.example.mall.agentservice.orchestration.tools;

import com.example.mall.agentservice.service.HealthFactCard;
import com.example.mall.agentservice.service.HealthMemoryService;
import com.example.mall.agentservice.service.UserPersonaCard;
import com.example.mall.agentservice.service.UserPersonaService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 记忆写入工具：L1 观点（recordOpinion）与 L3 画像（updatePersona）的参数解析与权限兜底。 */
class ProfileMemoryToolsTest {

    private final HealthMemoryService healthMemory = mock(HealthMemoryService.class);
    private final UserPersonaService personaService = mock(UserPersonaService.class);
    private final ProfileMemoryTools tools = new ProfileMemoryTools(healthMemory, personaService);

    @Test
    void recordOpinion_writesOpinionFact() {
        String out = tools.bind(1L).recordOpinion("剂型偏好", "怕苦，倾向外用药");

        verify(healthMemory).recordFact(1L, HealthFactCard.FactType.OPINION, "剂型偏好", "怕苦，倾向外用药");
        assertTrue(out.contains("已记录"));
    }

    @Test
    void recordOpinion_withoutUserId_refuses() {
        tools.bind(null).recordOpinion("剂型偏好", "怕苦");

        verifyNoInteractions(healthMemory);
    }

    @Test
    void updatePersona_parsesCsvLists() {
        String out = tools.bind(1L).updatePersona("老张", "简洁要点式", "控糖,减重", "怕苦、倾向外用药");

        ArgumentCaptor<UserPersonaCard> captor = ArgumentCaptor.forClass(UserPersonaCard.class);
        verify(personaService).upsertPersona(org.mockito.ArgumentMatchers.eq(1L), captor.capture());
        UserPersonaCard patch = captor.getValue();
        assertEquals("老张", patch.getNickname());
        assertEquals("简洁要点式", patch.getCommStyle());
        assertEquals(List.of("控糖", "减重"), patch.getHealthGoals());
        assertEquals(List.of("怕苦", "倾向外用药"), patch.getPreferences());
        assertTrue(out.contains("已更新"));
    }

    @Test
    void updatePersona_allBlank_skipsUpsert() {
        String out = tools.bind(1L).updatePersona("", " ", null, null);

        assertEquals("未提供需要更新的画像内容", out);
        verifyNoInteractions(personaService);
    }
}
