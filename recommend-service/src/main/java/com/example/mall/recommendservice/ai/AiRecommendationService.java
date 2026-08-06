package com.example.mall.recommendservice.ai;

import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.recommendservice.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 DeepSeek 大模型的智能推荐：
 * 构造 prompt（已购商品 + 全量目录）→ 调用 chat/completions → 解析 JSON → 三层校验
 * （id 必须在目录、不得是已购、去重）。任何一步失败都返回空列表，由上层回退规则推荐。
 */
@Service
public class AiRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(AiRecommendationService.class);

    /** 来源标记 */
    private static final String SOURCE_AI = "ai";

    private final DeepSeekClient deepSeekClient;
    private final DeepSeekProperties props;
    /** 注入 Spring 管理的 ObjectMapper（自带 JSR-310 模块，可序列化 LocalDateTime 等） */
    private final ObjectMapper objectMapper;

    public AiRecommendationService(DeepSeekClient deepSeekClient, DeepSeekProperties props, ObjectMapper objectMapper) {
        this.deepSeekClient = deepSeekClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** 互补推荐：返回空列表表示 AI 不可用/失败，调用方应回退规则推荐。本方法不抛异常。 */
    public List<RecommendationItemDTO> recommend(RecommendRequest request) {
        return recommendInternal(request, false);
    }

    /** 同类推荐（与已购商品同品类的其他商品）：语义同上，AI 不可用/失败返回空列表。 */
    public List<RecommendationItemDTO> recommendSimilar(RecommendRequest request) {
        return recommendInternal(request, true);
    }

    private List<RecommendationItemDTO> recommendInternal(RecommendRequest request, boolean similar) {
        // 统一 topN 语义：≤0 视为 0 即返回空（与编排层一致），避免向模型请求 0 条
        if (props.getTopN() <= 0) {
            log.info("topN 配置为 {}（≤0），AI 推荐关闭，交由规则推荐", props.getTopN());
            return List.of();
        }
        if (!deepSeekClient.isEnabled()) {
            log.info("未配置 deepseek.api-key，AI 推荐关闭，交由规则推荐");
            return List.of();
        }
        try {
            String raw = deepSeekClient.chatCompletion(buildMessages(request, similar));
            return parseAndValidate(raw, request, similar);
        } catch (Exception ex) {
            log.warn("DeepSeek 推荐失败，回退规则推荐: {}", ex.toString());
            return List.of();
        }
    }

    /* ---------- prompt 构造 ---------- */

    private List<DeepSeekChatRequest.Message> buildMessages(RecommendRequest request, boolean similar) throws Exception {
        String catalogJson = objectMapper.writeValueAsString(buildPayload(request));
        String system = similar
                ? """
                你是电商平台的智能导购助手。根据用户刚买过的商品，从商品目录中挑选与已购商品【同品类】的商品（同型号升级款、同品类其他型号或竞品），逐条给出简洁的中文推荐理由。
                要求：
                1) 只能从商品目录里选，绝不能推荐目录外的商品；
                2) 绝不能推荐用户已经购买过的商品；
                3) 推荐的商品必须与已购商品属于同一品类：以目录里每件商品的 category 字段为准；若某商品没有 category 字段，按商品名称推断；若某已购商品在目录中找不到同品类商品，可以不推该品类；
                4) 只输出严格的 JSON（配合 json_object 模式），不要任何多余文字、不要 Markdown 代码块；
                5) 最多输出 %d 条，按相关度从高到低排序。
                输出格式：{"recommendations":[{"productId":商品id数字,"reason":"30字以内的理由"}]}
                """.formatted(props.getTopN())
                : """
                你是电商平台的智能导购助手。根据用户刚买过的商品，从给定的商品目录中挑选最值得推荐的商品（互补、搭配或同类升级），逐条给出简洁的中文推荐理由。
                要求：
                1) 只能从商品目录里选，绝不能推荐目录外的商品；
                2) 绝不能推荐用户已经购买过的商品；
                3) 只输出严格的 JSON（配合 json_object 模式），不要任何多余文字、不要 Markdown 代码块；
                4) 最多输出 %d 条，按推荐合理度从高到低排序。
                输出格式：{"recommendations":[{"productId":商品id数字,"reason":"30字以内的理由"}]}
                """.formatted(props.getTopN());
        String user = "已购商品与商品目录如下（JSON）：\n" + catalogJson;
        return List.of(
                new DeepSeekChatRequest.Message("system", system),
                new DeepSeekChatRequest.Message("user", user));
    }

    /** 序列化给模型看的上下文：已购清单 + 目录，均取自请求（DTO 的 getter 即可直接序列化） */
    private Map<String, Object> buildPayload(RecommendRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purchasedItems", request.getPurchasedItems());
        payload.put("catalog", request.getCatalog());
        return payload;
    }

    /* ---------- 解析与校验 ---------- */

    private List<RecommendationItemDTO> parseAndValidate(String raw, RecommendRequest request, boolean similar) throws Exception {
        // 目录索引：productId -> ProductDTO
        Map<Long, ProductDTO> catalogIndex = new LinkedHashMap<>();
        if (request.getCatalog() != null) {
            for (ProductDTO p : request.getCatalog()) {
                catalogIndex.put(p.getId(), p);
            }
        }
        // 已购商品 id 集合
        Set<Long> purchasedIds = new LinkedHashSet<>();
        if (request.getPurchasedItems() != null) {
            for (OrderItemDTO item : request.getPurchasedItems()) {
                if (item.getProductId() != null) {
                    purchasedIds.add(item.getProductId());
                }
            }
        }
        // 已购品类集合（同类模式的第 4 层过滤用；解析不到时放宽，交由规则兜底）
        Set<String> purchasedCategories = new LinkedHashSet<>();
        if (similar) {
            for (Long purchasedId : purchasedIds) {
                ProductDTO p = catalogIndex.get(purchasedId);
                if (p != null && p.getCategory() != null && !p.getCategory().isBlank()) {
                    purchasedCategories.add(p.getCategory().trim());
                }
            }
        }
        // 第 4 层仅在同类模式且能解析到已购品类时启用
        boolean enforceCategory = similar && !purchasedCategories.isEmpty();

        // null/空白 content 直接返回空（回退规则），不依赖外层 catch 吞 NPE
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = stripCodeFence(raw);
        JsonNode root = objectMapper.readTree(json);
        JsonNode arr = root.path("recommendations");
        if (!arr.isArray()) {
            return List.of();
        }

        List<RecommendationItemDTO> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (JsonNode node : arr) {
            if (result.size() >= props.getTopN()) {
                break;
            }
            JsonNode idNode = node.get("productId");
            JsonNode reasonNode = node.get("reason");
            if (idNode == null || !idNode.isNumber()) {
                continue;
            }
            long productId = idNode.asLong();
            // 三层校验：在目录内、非已购、不重复；同类模式额外校验品类一致
            ProductDTO product = catalogIndex.get(productId);
            if (product == null || purchasedIds.contains(productId) || !seen.add(productId)) {
                continue;
            }
            if (enforceCategory) {
                String category = product.getCategory();
                if (category == null || category.isBlank()
                        || !purchasedCategories.contains(category.trim())) {
                    continue;
                }
            }
            RecommendationItemDTO item = new RecommendationItemDTO();
            item.setProductId(productId);
            item.setProductName(product.getName());
            item.setPrice(product.getPrice());
            item.setImageUrl(product.getImageUrl());
            item.setReason(reasonNode != null && reasonNode.isTextual()
                    ? reasonNode.asText() : "与您已购商品很搭配");
            item.setSource(SOURCE_AI);
            result.add(item);
        }
        return result;
    }

    /** 去掉模型偶尔可能包裹的 ```json ... ``` 围栏 */
    private String stripCodeFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int lastFence = s.lastIndexOf("```");
            if (lastFence > 0) {
                s = s.substring(0, lastFence);
            }
        }
        return s.trim();
    }
}
