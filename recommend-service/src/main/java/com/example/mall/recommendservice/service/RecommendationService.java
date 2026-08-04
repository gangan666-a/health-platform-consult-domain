package com.example.mall.recommendservice.service;

import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.common.model.RecommendationResponse;
import com.example.mall.recommendservice.ai.AiRecommendationService;
import com.example.mall.recommendservice.config.DeepSeekProperties;
import com.example.mall.recommendservice.rule.RuleRecommendationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 推荐编排：
 * 1) 先尝试 DeepSeek 大模型（AiRecommendationService 内部不抛异常，失败/未配置 Key 返回空）；
 * 2) AI 结果不足 topN 时，用规则推荐补足剩余名额；
 * 3) 每条结果自带 source（ai / rule），供前端区分展示。
 * 4) 按请求 type 分派模式：similar（同类）/ complement（互补，默认）。
 */
@Service
public class RecommendationService {

    private final AiRecommendationService aiService;
    private final RuleRecommendationService ruleService;
    private final DeepSeekProperties props;

    public RecommendationService(AiRecommendationService aiService,
                                 RuleRecommendationService ruleService,
                                 DeepSeekProperties props) {
        this.aiService = aiService;
        this.ruleService = ruleService;
        this.props = props;
    }

    public RecommendationResponse recommend(RecommendRequest request) {
        RecommendationResponse response = new RecommendationResponse();
        if (request == null || request.getCatalog() == null || request.getCatalog().isEmpty()) {
            response.setItems(List.of());
            return response;
        }
        int topN = Math.max(props.getTopN(), 1);
        boolean similar = "similar".equalsIgnoreCase(request.getType());

        List<RecommendationItemDTO> merged = new ArrayList<>();
        Set<Long> picked = new LinkedHashSet<>();
        for (RecommendationItemDTO item : similar
                ? aiService.recommendSimilar(request)
                : aiService.recommend(request)) {
            if (item.getProductId() != null && picked.add(item.getProductId())) {
                merged.add(item);
            }
        }
        // 用规则推荐补足剩余名额（避免 AI 只给了 1~2 条时页面太空）
        if (merged.size() < topN) {
            for (RecommendationItemDTO item : similar
                    ? ruleService.recommendSimilar(request, topN)
                    : ruleService.recommend(request, topN)) {
                if (merged.size() >= topN) {
                    break;
                }
                if (item.getProductId() != null && picked.add(item.getProductId())) {
                    merged.add(item);
                }
            }
        }
        response.setItems(merged);
        return response;
    }
}
