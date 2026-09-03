package com.example.mall.agentservice.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * 页型注册表：从 classpath report/pages/ 加载「基础契约 + 页型模板」组装为每页型的抽取配方。
 * 模板随代码进 git（可评审可版本化）；缺模板属编程错误，构造即失败（fail-fast），
 * 新增页型 = 加模板文件 + PageType 枚举值，注册表与编排零改动。
 * 构造器加载（非 @PostConstruct）：便于单测直接 new。
 */
@Component
public class PageTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(PageTypeRegistry.class);

    private static final String BASE_CONTRACT_PATH = "report/pages/_base-contract.md";
    private static final String PAGE_TEMPLATE_PATH = "report/pages/%s.md";

    private final Map<PageType, ExtractionRecipe> recipes = new EnumMap<>(PageType.class);

    public PageTypeRegistry() {
        String baseContract = readResource(BASE_CONTRACT_PATH);
        for (PageType type : PageType.values()) {
            String template = readResource(PAGE_TEMPLATE_PATH.formatted(templateName(type)));
            recipes.put(type, new ExtractionRecipe(type, baseContract + "\n" + template));
        }
        log.info("页型注册表加载完成：{} 个页型配方", recipes.size());
    }

    public ExtractionRecipe recipe(PageType type) {
        ExtractionRecipe recipe = recipes.get(type);
        if (recipe == null) {
            throw new IllegalStateException("页型配方缺失: " + type);
        }
        return recipe;
    }

    /** 枚举名小写即文件名（COVER → cover）。 */
    private static String templateName(PageType type) {
        return type.name().toLowerCase();
    }

    private static String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("页型 prompt 模板缺失或不可读: " + path, e);
        }
    }
}
