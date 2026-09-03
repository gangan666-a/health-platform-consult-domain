package com.example.mall.agentservice.report;

/** 文本层页抽取器：按页型配方（PageTypeRegistry）抽取结构化内容。 */
public interface PageExtractor {

    PageExtraction extract(PageContent page, PageType pageType);
}
