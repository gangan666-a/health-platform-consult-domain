package com.example.mall.agentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L3 人物画像卡：增量合并（标量覆盖、列表去重限量）与 JSON 往返保真。 */
class UserPersonaCardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    @Test
    void merge_overwritesScalar_dedupsList() {
        UserPersonaCard card = new UserPersonaCard();
        card.setNickname("老张");
        card.setHealthGoals(List.of("控糖"));

        UserPersonaCard patch = new UserPersonaCard();
        patch.setNickname("张工");
        patch.setHealthGoals(List.of("控糖", "减重"));
        card.merge(patch, TODAY);

        assertEquals("张工", card.getNickname(), "称呼后写为准");
        assertEquals(List.of("控糖", "减重"), card.getHealthGoals(), "列表去重追加");
        assertEquals(TODAY, card.getUpdatedAt());
    }

    @Test
    void merge_keepsAtMostTenListItems() {
        UserPersonaCard card = new UserPersonaCard();
        for (int i = 1; i <= 12; i++) {
            UserPersonaCard patch = new UserPersonaCard();
            patch.setPreferences(List.of("偏好" + i));
            card.merge(patch, TODAY);
        }

        assertEquals(10, card.getPreferences().size());
        assertEquals("偏好3", card.getPreferences().get(0), "超限滚动丢弃最旧");
        assertEquals("偏好12", card.getPreferences().get(9));
    }

    @Test
    void deriveSection_emptyWhenNothingCollected() {
        assertEquals("", new UserPersonaCard().deriveSection());
    }

    @Test
    void deriveSection_rendersAllFacets() {
        UserPersonaCard card = new UserPersonaCard();
        card.setNickname("老张");
        card.setCommStyle("简洁要点式");
        card.setHealthGoals(List.of("控糖", "减重"));
        card.setPreferences(List.of("怕苦，倾向外用药"));

        String section = card.deriveSection();

        assertTrue(section.contains("【用户画像"));
        assertTrue(section.contains("称呼：老张"));
        assertTrue(section.contains("沟通风格：简洁要点式"));
        assertTrue(section.contains("控糖、减重"));
        assertTrue(section.contains("怕苦，倾向外用药"));
    }

    @Test
    void jsonRoundTrip_preservesFields() throws Exception {
        UserPersonaCard card = new UserPersonaCard();
        card.setNickname("老张");
        card.setCommStyle("喜欢解释原理");
        card.setHealthGoals(List.of("控糖"));
        card.setPreferences(List.of("怕苦"));
        card.setUpdatedAt(TODAY);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        UserPersonaCard back = mapper.readValue(mapper.writeValueAsString(card), UserPersonaCard.class);

        assertEquals("老张", back.getNickname());
        assertEquals("喜欢解释原理", back.getCommStyle());
        assertEquals(List.of("控糖"), back.getHealthGoals());
        assertEquals(TODAY, back.getUpdatedAt());
    }
}
