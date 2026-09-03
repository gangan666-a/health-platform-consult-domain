package com.example.mall.agentservice.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.mall.agentservice.config.EsKnowledgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * 启动时按 mapping 设计创建缺失的医疗知识索引（别名 + 版本化 backing index），
 * 并对已存在的索引<b>增量同步 mapping</b>（P0-1 Schema 治理）。
 * <p>
 * 增量同步的必要性：所有 knowledge_* 索引均为 {@code dynamic: strict}，
 * 新增字段（如 P0-2 的 source / evidence_grade / version）若不先演进 mapping，
 * 灌库会被 ES 以「字段未定义」拒绝，而原地建索引的路径又因别名已存在被跳过。
 * ES 的 _mapping API 允许为已有索引追加新字段定义，正是此处的演进通道。
 * <p>
 * ES 不可用 / IK 插件缺失时仅告警、不阻断启动（对齐 T-2 ES 降级纪律）。
 */
@Component
public class KnowledgeIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexInitializer.class);

    private final ElasticsearchClient client;
    private final EsKnowledgeProperties props;
    private final ObjectMapper objectMapper;

    public KnowledgeIndexInitializer(ElasticsearchClient client, EsKnowledgeProperties props,
                                     ObjectMapper objectMapper) {
        this.client = client;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isInitEnabled()) {
            log.info("ES 知识索引初始化已关闭（es.knowledge.init-enabled=false），跳过");
            return;
        }
        try {
            int created = ensureIndexes();
            int synced = syncMappings();
            log.info("ES 知识索引初始化完成，本次新建 {} 个，mapping 演进 {} 个", created, synced);
        } catch (Exception e) {
            log.warn("ES 知识索引初始化失败（ES 未启动或 IK 插件未安装？）: {}", e.getMessage());
        }
    }

    /** 为每个缺失的别名创建 backing index + 别名；幂等。返回新建个数。 */
    public synchronized int ensureIndexes() throws IOException {
        int created = 0;
        for (KnowledgeIndex idx : KnowledgeIndex.values()) {
            if (client.indices().existsAlias(b -> b.name(idx.alias())).value()) {
                continue;
            }
            createIndex(idx);
            created++;
        }
        return created;
    }

    /**
     * 对已存在的索引追加 mapping 中新增的字段定义（幂等；只发 mappings 节点，
     * settings/analysis 不在 _mapping API 的允许范围内）。
     *
     * @return 成功同步的索引数
     */
    private int syncMappings() throws IOException {
        int synced = 0;
        for (KnowledgeIndex idx : KnowledgeIndex.values()) {
            if (!client.indices().existsAlias(b -> b.name(idx.alias())).value()) {
                continue;
            }
            try {
                JsonNode mappings = objectMapper.readTree(loadMapping(idx)).get("mappings");
                if (mappings == null) {
                    continue;
                }
                client.indices().putMapping(b -> b
                        .index(idx.alias())
                        .withJson(new StringReader(mappings.toString())));
                synced++;
            } catch (Exception e) {
                log.warn("mapping 演进失败（{}）: {}", idx.alias(), e.getMessage());
            }
        }
        return synced;
    }

    private void createIndex(KnowledgeIndex idx) throws IOException {
        String backing = idx.currentBackingIndex();
        String mappingJson = loadMapping(idx);
        client.indices().create(b -> b.index(backing).withJson(new StringReader(mappingJson)));
        client.indices().putAlias(b -> b.index(backing).name(idx.alias()));
        log.info("已创建 ES 知识索引: alias={} index={}", idx.alias(), backing);
    }

    private String loadMapping(KnowledgeIndex idx) throws IOException {
        ClassPathResource resource = new ClassPathResource(idx.mappingResource().substring("classpath:".length()));
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
