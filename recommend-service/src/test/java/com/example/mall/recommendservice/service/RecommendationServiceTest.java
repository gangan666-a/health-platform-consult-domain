package com.example.mall.recommendservice.service;

import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.common.model.RecommendationResponse;
import com.example.mall.recommendservice.ai.AiRecommendationService;
import com.example.mall.recommendservice.config.DeepSeekProperties;
import com.example.mall.recommendservice.rule.RuleRecommendationService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RecommendationService（编排层）单元测试：
 * 验证 AI → 规则 的降级编排、type 分派（similar/complement/缺省/非法）、跨 AI/规则去重、topN 边界。
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private AiRecommendationService aiService;
    @Mock
    private RuleRecommendationService ruleService;

    private DeepSeekProperties props;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        props = new DeepSeekProperties();
        props.setTopN(4);
        service = new RecommendationService(aiService, ruleService, props);
    }

    /* ---------- 路由 / type 分派 ---------- */

    @Test
    @DisplayName("type=similar：走同类服务（AI.recommendSimilar + 规则补足）")
    void typeSimilar_routesToAiAndRuleSimilar() {
        RecommendRequest req = request("similar");
        when(aiService.recommendSimilar(req)).thenReturn(List.of(recItem(2L, "ai")));
        when(ruleService.recommendSimilar(req, 4)).thenReturn(List.of());

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSource()).isEqualTo("ai");
        verify(aiService, never()).recommend(any());
        verify(ruleService, never()).recommend(any(), anyInt());
        verify(ruleService).recommendSimilar(eq(req), eq(4));
    }

    @Test
    @DisplayName("type=complement：走互补服务（AI.recommend + 规则补足）")
    void typeComplement_routesToAiAndRuleComplement() {
        RecommendRequest req = request("complement");
        when(aiService.recommend(req)).thenReturn(List.of(recItem(2L, "ai")));
        when(ruleService.recommend(req, 4)).thenReturn(List.of());

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSource()).isEqualTo("ai");
        verify(aiService, never()).recommendSimilar(any());
        verify(ruleService, never()).recommendSimilar(any(), anyInt());
        verify(ruleService).recommend(eq(req), eq(4));
    }

    @Test
    @DisplayName("type 缺省（null）：按默认 complement 处理")
    void typeNull_defaultsToComplement() {
        RecommendRequest req = request(null);
        when(aiService.recommend(req)).thenReturn(List.of(recItem(2L, "ai")));
        when(ruleService.recommend(req, 4)).thenReturn(List.of());

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(1);
        verify(aiService).recommend(req);
        verify(aiService, never()).recommendSimilar(any());
    }

    @Test
    @DisplayName("type 非法值：不 500，按默认 complement 处理（实现验证）")
    void typeIllegalValue_treatedAsComplement() {
        RecommendRequest req = request("bogus-type");
        when(aiService.recommend(req)).thenReturn(List.of());
        when(ruleService.recommend(req, 4)).thenReturn(List.of(recItem(2L, "rule")));

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSource()).isEqualTo("rule");
        verify(aiService, never()).recommendSimilar(any());
    }

    /* ---------- 降级编排 ---------- */

    @Test
    @DisplayName("AI 返回空（模拟 AI 内部失败/无 Key）→ 回退规则推荐")
    void aiReturnsEmpty_fallsBackToRule() {
        RecommendRequest req = request("complement");
        when(aiService.recommend(req)).thenReturn(List.of());
        when(ruleService.recommend(req, 4)).thenReturn(List.of(recItem(5L, "rule"), recItem(6L, "rule")));

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(2);
        assertThat(resp.getItems()).allSatisfy(i -> assertThat(i.getSource()).isEqualTo("rule"));
        assertThat(resp.getItems().stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("AI 与规则都返回空 → 返回空列表，不 500")
    void aiAndRuleBothEmpty_returnsEmptyItems() {
        RecommendRequest req = request("complement");
        when(aiService.recommend(req)).thenReturn(List.of());
        when(ruleService.recommend(req, 4)).thenReturn(List.of());

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).isEmpty();
    }

    @Test
    @DisplayName("catalog 为空 → 直接返回空列表，不调用任何下游")
    void catalogEmpty_returnsEmptyWithoutCallingServices() {
        RecommendRequest req = request("complement");
        req.setCatalog(List.of());

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).isEmpty();
        verifyNoInteractions(aiService, ruleService);
    }

    @Test
    @DisplayName("request 为 null → 返回空列表，不 500")
    void requestNull_returnsEmpty() {
        RecommendationResponse resp = service.recommend(null);
        assertThat(resp.getItems()).isEmpty();
    }

    @Test
    @DisplayName("purchasedItems 为空 → 不 500，返回规则补足结果")
    void purchasedItemsEmpty_returnsReasonableResult() {
        RecommendRequest req = request("complement");
        req.setPurchasedItems(List.of());
        when(aiService.recommend(req)).thenReturn(List.of());
        when(ruleService.recommend(req, 4)).thenReturn(List.of(recItem(2L, "rule"), recItem(3L, "rule")));

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(2);
        assertThat(resp.getItems()).allSatisfy(i -> assertThat(i.getSource()).isEqualTo("rule"));
    }

    /* ---------- 异常与边界（防 happy path） ---------- */

    @Test
    @DisplayName("AI 服务抛异常 → 编排层兜底，回退规则推荐（不 500）")
    void aiServiceThrows_fallsBackToRule() {
        RecommendRequest req = request("complement");
        when(aiService.recommend(req)).thenThrow(new RuntimeException("deepseek down"));
        when(ruleService.recommend(req, 4)).thenReturn(List.of(recItem(5L, "rule"), recItem(6L, "rule")));

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(2);
        assertThat(resp.getItems()).allSatisfy(i -> assertThat(i.getSource()).isEqualTo("rule"));
        assertThat(resp.getItems().stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(5L, 6L);
        verify(ruleService).recommend(eq(req), eq(4));
    }

    @Test
    @DisplayName("AI 返回超过 topN 条 → 编排层统一截断至 topN（取前 topN，保持顺序）")
    void aiReturnsMoreThanTopN_truncatedToTopN() {
        RecommendRequest req = request("complement");
        when(aiService.recommend(req)).thenReturn(List.of(
                recItem(11L, "ai"), recItem(12L, "ai"), recItem(13L, "ai"),
                recItem(14L, "ai"), recItem(15L, "ai")));

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(4);
        assertThat(resp.getItems().stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(11L, 12L, 13L, 14L);
    }

    @Test
    @DisplayName("AI 与规则返回重叠商品 → picked 集合跨来源去重，AI 优先")
    void dedupeAcrossAiAndRule() {
        RecommendRequest req = request("complement");
        when(aiService.recommend(req)).thenReturn(List.of(recItem(1L, "ai"), recItem(2L, "ai")));
        when(ruleService.recommend(req, 4)).thenReturn(List.of(recItem(1L, "rule"), recItem(3L, "rule")));

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems().stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(1L, 2L, 3L);
        assertThat(resp.getItems().get(0).getSource()).isEqualTo("ai");
    }

    @Test
    @DisplayName("AI 返回条数刚好等于 topN → 不再规则补足，结果 ≤ topN")
    void resultRespectsTopN_whenAiWithinCap() {
        RecommendRequest req = request("complement");
        when(aiService.recommend(req)).thenReturn(List.of(
                recItem(1L, "ai"), recItem(2L, "ai"), recItem(3L, "ai"), recItem(4L, "ai")));

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).hasSize(4);
        verify(ruleService, never()).recommend(any(), anyInt());
    }

    @Test
    @DisplayName("topN=0 → 语义统一为返回空列表（与 AI 层 topN=0 返回空一致），不调用任何下游")
    void topNZero_returnsEmpty() {
        props.setTopN(0);
        RecommendRequest req = request("complement");

        RecommendationResponse resp = service.recommend(req);

        assertThat(resp.getItems()).isEmpty();
        verifyNoInteractions(aiService, ruleService);
    }

    /* ---------- 工具方法 ---------- */

    private RecommendRequest request(String type) {
        RecommendRequest req = new RecommendRequest();
        req.setType(type);
        req.setCatalog(List.of(
                product(1L, "iPhone 15 Pro", "手机", new BigDecimal("7999")),
                product(2L, "AirPods Pro", "耳机", new BigDecimal("1899")),
                product(3L, "Apple Watch", "智能手表", new BigDecimal("2999"))));
        req.setPurchasedItems(List.of(item(1L, "iPhone 15 Pro")));
        return req;
    }

    private ProductDTO product(long id, String name, String category, BigDecimal price) {
        ProductDTO p = new ProductDTO();
        p.setId(id);
        p.setName(name);
        p.setCategory(category);
        p.setPrice(price);
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

    private RecommendationItemDTO recItem(long id, String source) {
        RecommendationItemDTO d = new RecommendationItemDTO();
        d.setProductId(id);
        d.setProductName("P" + id);
        d.setPrice(BigDecimal.valueOf(100));
        d.setSource(source);
        return d;
    }
}
