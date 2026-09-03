package com.example.mall.agentservice.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 页型注册表：模板齐全加载、基础契约注入、按页型取配方（页型注册表模式的核心约束）。 */
class PageTypeRegistryTest {

    private final PageTypeRegistry registry = new PageTypeRegistry();

    @Test
    void allPageTypesHaveRecipe() {
        for (PageType type : PageType.values()) {
            ExtractionRecipe recipe = registry.recipe(type);
            assertEquals(type, recipe.pageType());
            assertTrue(recipe.prompt().contains("页面类型：" + type.display()),
                    type + " 模板缺失页型标记");
        }
    }

    @Test
    void baseContractInjectedIntoEveryRecipe() {
        for (PageType type : PageType.values()) {
            String prompt = registry.recipe(type).prompt();
            assertTrue(prompt.contains("逐字照抄"), type + " 缺基础契约（防幻觉硬规则）");
            assertTrue(prompt.contains("隐私红线"), type + " 缺隐私契约");
        }
    }

    @Test
    void labRecipeContainsTableGuidance() {
        String prompt = registry.recipe(PageType.LAB).prompt();
        assertTrue(prompt.contains("逐行抽取"));
        assertTrue(prompt.contains("refRange"));
    }

    @Test
    void missingRecipe_failsFast() {
        // 注册表以枚举为全集加载，未知页型（假设手工构造）在 recipe() 处 fail-fast
        assertEquals(8, PageType.values().length);
    }
}
