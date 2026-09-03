package com.example.mall.agentservice.report;

/**
 * 页型抽取配方（页型注册表的条目）：该页型的完整抽取 prompt（基础契约 + 页型定制）。
 * 模板文件在 resources/report/pages/ 下随代码进 git，可评审可版本化；
 * 新增页型 = 加一个模板文件 + PageType 枚举值 + 注册表无代码改动。
 */
public record ExtractionRecipe(PageType pageType, String prompt) {
}
