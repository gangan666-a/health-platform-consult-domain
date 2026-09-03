package com.example.mall.agentservice.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 自定义 ES 客户端：绕开 Spring AI 固定 mapping 的向量库自动配置（已 exclude），
 * 用原生 ES Java Client 手工建索引与检索（架构决策 D1）。
 */
@Configuration
@EnableConfigurationProperties(EsKnowledgeProperties.class)
public class EsClientConfig {

    private static final Logger log = LoggerFactory.getLogger(EsClientConfig.class);

    @Bean
    public ElasticsearchClient elasticsearchClient(EsKnowledgeProperties props) {
        List<String> uris = props.getUris();
        HttpHost[] hosts = uris.stream()
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
        RestClient restClient = RestClient.builder(hosts).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        log.info("ES 知识库客户端已装配: uris={}", uris);
        return new ElasticsearchClient(transport);
    }
}
