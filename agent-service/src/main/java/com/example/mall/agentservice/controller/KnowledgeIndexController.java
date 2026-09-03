package com.example.mall.agentservice.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.mall.agentservice.es.KnowledgeIndex;
import com.example.mall.agentservice.es.KnowledgeIndexInitializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ES 知识索引运维端点：查看索引状态 / 手动触发建索引（幂等）。
 */
@RestController
@RequestMapping("/api/es")
public class KnowledgeIndexController {

    private final ElasticsearchClient client;
    private final KnowledgeIndexInitializer initializer;

    public KnowledgeIndexController(ElasticsearchClient client, KnowledgeIndexInitializer initializer) {
        this.client = client;
        this.initializer = initializer;
    }

    /** 列出 10 个逻辑索引的别名与 backing index 状态 */
    @GetMapping("/indexes")
    public Map<String, Object> indexes() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (KnowledgeIndex idx : KnowledgeIndex.values()) {
                boolean exists = client.indices().existsAlias(b -> b.name(idx.alias())).value();
                list.add(Map.of(
                        "name", idx.name(),
                        "alias", idx.alias(),
                        "backingIndex", idx.currentBackingIndex(),
                        "created", exists
                ));
            }
            return Map.of("indexes", list);
        } catch (Exception e) {
            return Map.of("error", "ES 不可用: " + e.getMessage());
        }
    }

    /** 幂等触发建索引；缺失的别名才新建 */
    @PostMapping("/init-indexes")
    public Map<String, Object> initIndexes() {
        try {
            int created = initializer.ensureIndexes();
            return Map.of("created", created, "total", KnowledgeIndex.values().length);
        } catch (Exception e) {
            return Map.of("error", "建索引失败（ES 未启动或 IK 插件未安装?）: " + e.getMessage());
        }
    }
}
