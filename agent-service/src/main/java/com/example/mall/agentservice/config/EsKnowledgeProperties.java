package com.example.mall.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * ES 知识库连接配置（自定义 ES Java Client，非 Spring AI 向量库自动配置）。
 */
@ConfigurationProperties(prefix = "es.knowledge")
public class EsKnowledgeProperties {

    /** ES 节点地址列表，默认本地演示节点 */
    private List<String> uris = new ArrayList<>(List.of("http://localhost:9200"));

    /** 启动时是否自动创建缺失索引；ES 未装 IK 插件时建索引会失败，仅告警不阻断启动 */
    private boolean initEnabled = true;

    public List<String> getUris() {
        return uris;
    }

    public void setUris(List<String> uris) {
        this.uris = uris;
    }

    public boolean isInitEnabled() {
        return initEnabled;
    }

    public void setInitEnabled(boolean initEnabled) {
        this.initEnabled = initEnabled;
    }
}
