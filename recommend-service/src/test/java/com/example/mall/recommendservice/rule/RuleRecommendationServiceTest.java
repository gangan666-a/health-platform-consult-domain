package com.example.mall.recommendservice.rule;

import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RuleRecommendationService（规则兜底）单元测试：
 * 覆盖同类（同品类/关键词兜底/补足策略）、互补（权重排序/热销补足/补足去重）、
 * 以及 topN≤0、目录只有已购商品、catalog 为空等边界。
 */
class RuleRecommendationServiceTest {

    private final RuleRecommendationService service = new RuleRecommendationService();

    /* ---------- 同类（similar） ---------- */

    @Test
    @DisplayName("同类：按 category 推荐同品类、排除已购、价格降序 → id 升序")
    void similar_recommendsSameCategory_excludesPurchased() {
        RecommendRequest req = request("similar", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "iPhone 16", "手机", "8999"),
                product(3L, "Mate 60 Pro", "手机", "6999"),
                product(4L, "AirPods Pro", "耳机", "1899")), List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommendSimilar(req, 2);

        assertThat(result).hasSize(2);
        // 同品类、排除已购 id1、价格降序
        assertThat(result.stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(2L, 3L);
        assertThat(result).allSatisfy(i -> {
            assertThat(i.getSource()).isEqualTo("rule");
            assertThat(i.getReason()).contains("同品类");
            assertThat(i.getReason()).contains("iPhone 15 Pro"); // 锚点已购商品
        });
    }

    @Test
    @DisplayName("同类：无同品类商品 → 用互补/热销规则补足，不返回空（补足策略验证）")
    void similar_noSameCategory_usesComplementFill() {
        // 已购手机，但目录只有耳机（互补权重高）
        RecommendRequest req = request("similar", List.of(
                product(4L, "AirPods Pro", "耳机", "1899"),
                product(5L, "WH-1000XM5", "耳机", "2899")), List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommendSimilar(req, 2);

        assertThat(result).isNotEmpty();
        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(i -> {
            assertThat(i.getSource()).isEqualTo("rule");
            assertThat(i.getProductId()).isNotEqualTo(1L);
        });
    }

    @Test
    @DisplayName("同类：catalog 商品 category 缺失 → 按商品名关键词兜底品类")
    void similar_keywordCategoryFallback() {
        // 目录商品均无 category，靠名称关键词推断为「手机」
        RecommendRequest req = request("similar", List.of(
                product(1L, "iPhone 15 Pro", null, "7999"),
                product(2L, "iPhone 16", null, "8999"),
                product(3L, "MacBook Air", null, "9999")), List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommendSimilar(req, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(2L); // iPhone 16 关键词推断「手机」
    }

    @Test
    @DisplayName("同类：已购商品不在目录中（快照无 category）→ 按已购名称关键词兜底品类")
    void similar_purchasedKeywordFallback() {
        // 已购 id999 不在目录，名称「华为 Mate 60」关键词推断「手机」
        RecommendRequest req = request("similar", List.of(
                product(2L, "Mate 60 Pro", null, "6999"),
                product(3L, "MacBook Air", null, "9999")), List.of(item(999L, "华为 Mate 60")));

        List<RecommendationItemDTO> result = service.recommendSimilar(req, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("同类：目录只有已购商品 → 返回空")
    void similar_emptyWhenOnlyPurchasedInCatalog() {
        RecommendRequest req = request("similar", List.of(product(1L, "iPhone 15 Pro", "手机", "7999")),
                List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommendSimilar(req, 4);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("同类：topN=0/负数 → 返回空")
    void similar_topNZeroOrNegative_empty() {
        RecommendRequest req = request("similar", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "iPhone 16", "手机", "8999")), List.of(item(1L, "iPhone 15 Pro")));

        assertThat(service.recommendSimilar(req, 0)).isEmpty();
        assertThat(service.recommendSimilar(req, -1)).isEmpty();
    }

    @Test
    @DisplayName("同类：catalog 为 null/空 → 返回空")
    void similar_catalogNullOrEmpty_empty() {
        RecommendRequest nullReq = request("similar", null, List.of(item(1L, "iPhone 15 Pro")));
        RecommendRequest emptyReq = request("similar", List.of(), List.of(item(1L, "iPhone 15 Pro")));

        assertThat(service.recommendSimilar(nullReq, 4)).isEmpty();
        assertThat(service.recommendSimilar(emptyReq, 4)).isEmpty();
    }

    /* ---------- 互补（complement） ---------- */

    @Test
    @DisplayName("互补：按互补权重确定性排序（分数降序→价格降序→id 升序），排除已购")
    void complement_weightsDeterministicOrder() {
        RecommendRequest req = request("complement", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "AirPods Pro", "耳机", "1899"),     // 手机|耳机=3
                product(3L, "Apple Watch", "智能手表", "2999"),  // 手机|智能手表=2
                product(4L, "充电宝", "数码配件", "199"),        // 手机|数码配件=2
                product(5L, "iPad", "平板", "4999"),             // 手机|平板=1
                product(6L, "iPhone 16", "手机", "8999"),        // 手机|手机=1
                product(7L, "Nike Air Max", "运动鞋", "799")),   // 无互补分=0
                List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommend(req, 5);

        assertThat(result).hasSize(5);
        assertThat(result.stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(2L, 3L, 4L, 6L, 5L);
        assertThat(result).allSatisfy(i -> {
            assertThat(i.getSource()).isEqualTo("rule");
            assertThat(i.getProductId()).isNotEqualTo(1L); // 排除已购
        });
        // 互补理由带锚点已购商品
        assertThat(result.get(0).getReason()).contains("iPhone 15 Pro");
    }

    @Test
    @DisplayName("互补：排除已购商品")
    void complement_excludesPurchased() {
        RecommendRequest req = request("complement", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "AirPods Pro", "耳机", "1899")), List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommend(req, 4);

        assertThat(result.stream().map(RecommendationItemDTO::getProductId))
                .doesNotContain(1L);
        assertThat(result).extracting(RecommendationItemDTO::getProductId).containsExactly(2L);
    }

    @Test
    @DisplayName("互补：互补商品不足时热销补足，补足阶段不得重复（刚修的 bug，验证去重）")
    void complement_fillWithHotSale_noDuplicate() {
        // 只有 2 个有互补分的商品（id2=3 分、id8=1 分）+ 1 个无互补分商品（id7），topN=3
        RecommendRequest req = request("complement", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "AirPods Pro", "耳机", "1899"),
                product(8L, "MacBook Air", "电脑", "9999"),
                product(7L, "Nike Air Max", "运动鞋", "799")), List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommend(req, 3);

        // 第一段：id2、id8（互补分>0）；补足段：id7（热销），且不能重复第一段已选商品
        assertThat(result).hasSize(3);
        assertThat(result.stream().map(RecommendationItemDTO::getProductId))
                .containsExactly(2L, 8L, 7L);
        long distinct = result.stream().map(RecommendationItemDTO::getProductId).distinct().count();
        assertThat(distinct).isEqualTo(result.size()); // 无重复
    }

    @Test
    @DisplayName("互补：结果数量 ≤ topN（候选多但 topN 小）")
    void complement_resultWithinTopN() {
        RecommendRequest req = request("complement", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "AirPods Pro", "耳机", "1899"),
                product(3L, "Apple Watch", "智能手表", "2999"),
                product(4L, "充电宝", "数码配件", "199")), List.of(item(1L, "iPhone 15 Pro")));

        List<RecommendationItemDTO> result = service.recommend(req, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("互补：catalog 只有已购商品 → 返回空")
    void complement_catalogOnlyPurchased_empty() {
        RecommendRequest req = request("complement", List.of(product(1L, "iPhone 15 Pro", "手机", "7999")),
                List.of(item(1L, "iPhone 15 Pro")));

        assertThat(service.recommend(req, 4)).isEmpty();
    }

    @Test
    @DisplayName("互补：topN=0/负数 → 返回空")
    void complement_topNZeroOrNegative_empty() {
        RecommendRequest req = request("complement", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "AirPods Pro", "耳机", "1899")), List.of(item(1L, "iPhone 15 Pro")));

        assertThat(service.recommend(req, 0)).isEmpty();
        assertThat(service.recommend(req, -1)).isEmpty();
    }

    @Test
    @DisplayName("互补：catalog 为 null/空 → 返回空")
    void complement_catalogNullOrEmpty_empty() {
        RecommendRequest nullReq = request("complement", null, List.of(item(1L, "iPhone 15 Pro")));
        RecommendRequest emptyReq = request("complement", List.of(), List.of(item(1L, "iPhone 15 Pro")));

        assertThat(service.recommend(nullReq, 4)).isEmpty();
        assertThat(service.recommend(emptyReq, 4)).isEmpty();
    }

    @Test
    @DisplayName("互补：purchasedItems 为 null → 不 500，热销补足")
    void purchasedNull_hotSaleFill_noException() {
        RecommendRequest req = request("complement", List.of(
                product(1L, "iPhone 15 Pro", "手机", "7999"),
                product(2L, "AirPods Pro", "耳机", "1899")), null);

        List<RecommendationItemDTO> result = service.recommend(req, 2);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(i -> assertThat(i.getSource()).isEqualTo("rule"));
    }

    /* ---------- 工具方法 ---------- */

    private RecommendRequest request(String type, List<ProductDTO> catalog, List<OrderItemDTO> purchased) {
        RecommendRequest req = new RecommendRequest();
        req.setType(type);
        req.setCatalog(catalog);
        req.setPurchasedItems(purchased);
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
