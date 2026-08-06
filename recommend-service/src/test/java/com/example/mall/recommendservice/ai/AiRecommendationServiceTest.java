package com.example.mall.recommendservice.ai;

import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.recommendservice.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiRecommendationService（DeepSeek 幻觉过滤链）单元测试：
 * 覆盖「目录内 → 非已购 → 去重 →（同类）品类一致」四层校验、
 * 非法 JSON/空 content/reasoning_content、无 Key、异常/超时、超出 topN 截断、代码围栏剥离。
 */
@ExtendWith(MockitoExtension.class)
class AiRecommendationServiceTest {

    @Mock
    private DeepSeekClient deepSeekClient;

    private DeepSeekProperties props;
    private AiRecommendationService service;

    @BeforeEach
    void setUp() {
        props = new DeepSeekProperties();
        props.setTopN(4);
        service = new AiRecommendationService(deepSeekClient, props, new ObjectMapper());
    }

    /* ---------- 正常路径 ---------- */

    @Test
    @DisplayName("正常：模型返回合法 JSON → 解析为 items，source=ai")
    void validJson_parsedItems_sourceAi() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":2,\"reason\":\"升级款\"}]}");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).hasSize(1);
        RecommendationItemDTO item = result.get(0);
        assertThat(item.getProductId()).isEqualTo(2L);
        assertThat(item.getProductName()).isEqualTo("iPhone 16");
        assertThat(item.getReason()).isEqualTo("升级款");
        assertThat(item.getSource()).isEqualTo("ai");
    }

    @Test
    @DisplayName("幻觉过滤①：目录外商品（模型发明的 id）→ 丢弃")
    void outOfCatalogItem_filtered() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":2,\"reason\":\"ok\"},{\"productId\":99,\"reason\":\"幻觉\"}]}");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("幻觉过滤②：推荐已购商品 → 丢弃")
    void purchasedItem_filtered() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":1,\"reason\":\"已购\"},{\"productId\":2,\"reason\":\"ok\"}]}");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("幻觉过滤③：重复商品（多条相同 id）→ 去重，仅保留首条")
    void duplicateItem_deduped() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":2,\"reason\":\"a\"},{\"productId\":2,\"reason\":\"b\"},{\"productId\":3,\"reason\":\"c\"}]}");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result.stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("幻觉过滤④（同类）：推荐异品类商品 → 品类一致校验过滤；同品类保留")
    void similar_wrongCategory_filtered_sameCategoryKept() {
        RecommendRequest req = request("similar");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":2,\"reason\":\"手机\"},{\"productId\":3,\"reason\":\"耳机\"}]}");

        List<RecommendationItemDTO> result = service.recommendSimilar(req);

        // id2=手机（同品类保留），id3=耳机（异品类被过滤）
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(2L);
        assertThat(result.get(0).getSource()).isEqualTo("ai");
    }

    @Test
    @DisplayName("同类：已购品类解析不到 → 品类校验放宽（交由规则兜底）")
    void similar_purchasedCategoryUnresolvable_relaxesFilter() {
        // 已购 id999 不在目录、名称无关键词 → purchasedCategories 为空 → 品类校验关闭
        RecommendRequest req = request("similar");
        req.setPurchasedItems(List.of(item(999L, "神秘物品")));
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":2,\"reason\":\"任意\"}]}");

        List<RecommendationItemDTO> result = service.recommendSimilar(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(2L);
    }

    /* ---------- 解析失败 / 降级（不 500） ---------- */

    @Test
    @DisplayName("模型返回非法 JSON → 解析失败，返回空（回退规则），不抛异常")
    void invalidJson_returnsEmpty_noThrow() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn("not a json at all");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("模型返回空 content → 返回空，不抛异常")
    void emptyContent_returnsEmpty() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn("");

        assertThat(service.recommend(req)).isEmpty();
    }

    @Test
    @DisplayName("思考模式未关：content 为 reasoning 文本而非 JSON → 解析失败返回空")
    void reasoningContentAsContent_parseFail_empty() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "让我想想，用户买了手机，应该推荐耳机，因为...");

        assertThat(service.recommend(req)).isEmpty();
    }

    @Test
    @DisplayName("DeepSeekClient 抛异常（网络/超时/5xx）→ 返回空，不 500")
    void chatCompletionThrows_returnsEmpty_noThrow() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenThrow(new RuntimeException("connect timeout"));

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("chatCompletion 返回 null → 内部 NPE 被捕获，返回空（不 500）")
    void chatCompletionReturnsNull_returnsEmpty() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(null);

        assertThat(service.recommend(req)).isEmpty();
    }

    @Test
    @DisplayName("无 API Key（isEnabled=false）→ 直接返回空，不调用 chatCompletion")
    void noApiKey_disabled_returnsEmpty_noClientCall() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(false);

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).isEmpty();
        verify(deepSeekClient, never()).chatCompletion(any());
    }

    /* ---------- 条数 / 结构边界 ---------- */

    @Test
    @DisplayName("模型返回超过 topN 条 → 截断至 topN")
    void exceedsTopN_truncated() {
        props.setTopN(2);
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":2,\"reason\":\"a\"},{\"productId\":3,\"reason\":\"b\"},{\"productId\":4,\"reason\":\"c\"}]}");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("模型返回带 Markdown 围栏 ```json``` → 围栏剥离后正常解析")
    void codeFence_stripped() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "```json\n{\"recommendations\":[{\"productId\":2,\"reason\":\"围栏\"}]}\n```");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("模型返回结构不含 recommendations 数组 → 返回空")
    void recommendationsNotArray_empty() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn("{\"recommendations\":\"oops\"}");

        assertThat(service.recommend(req)).isEmpty();
    }

    @Test
    @DisplayName("模型返回条目缺 reason → 使用默认理由文案")
    void reasonMissing_usesDefaultReason() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn("{\"recommendations\":[{\"productId\":2}]}");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReason()).isEqualTo("与您已购商品很搭配");
    }

    @Test
    @DisplayName("模型返回 productId 非数字 → 该条被跳过")
    void nonNumericProductId_skipped() {
        RecommendRequest req = request("complement");
        when(deepSeekClient.isEnabled()).thenReturn(true);
        when(deepSeekClient.chatCompletion(any())).thenReturn(
                "{\"recommendations\":[{\"productId\":\"2\",\"reason\":\"字符串id\"},{\"productId\":3,\"reason\":\"ok\"}]}");

        List<RecommendationItemDTO> result = service.recommend(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(3L);
    }

    /* ---------- 工具方法 ---------- */

    private RecommendRequest request(String type) {
        RecommendRequest req = new RecommendRequest();
        req.setType(type);
        req.setCatalog(List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "iPhone 16", "手机", "8999"),
                product(3L, "AirPods Pro", "耳机", "1899"),
                product(4L, "Apple Watch", "智能手表", "2999")));
        req.setPurchasedItems(List.of(item(1L, "iPhone 15 Pro")));
        return req;
    }

    private ProductDTO product(long id, String name, String category, String price) {
        ProductDTO p = new ProductDTO();
        p.setId(id);
        p.setName(name);
        p.setCategory(category);
        p.setPrice(new BigDecimal(price));
        p.setImageUrl("img-" + id);
        return p;
    }

    private OrderItemDTO item(long id, String name) {
        OrderItemDTO o = new OrderItemDTO();
        o.setProductId(id);
        o.setProductName(name);
        o.setQuantity(1);
        return o;
    }
}
