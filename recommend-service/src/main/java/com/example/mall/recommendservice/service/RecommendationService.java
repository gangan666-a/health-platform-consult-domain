package com.example.mall.recommendservice.service;

import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import com.example.mall.common.model.RecommendationResponse;
import com.example.mall.recommendservice.ai.AiRecommendationService;
import com.example.mall.recommendservice.config.DeepSeekProperties;
import com.example.mall.recommendservice.rule.RuleRecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 5) 防御性兜底：AI 抛任何异常回退规则；规则也异常时返回空列表；结果统一截断 topN，绝不 500。
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

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
        // 统一 topN 语义：≤0 视为 0 即返回空（与 AiRecommendationService 一致），绝不归一化为 1
        int topN = Math.max(props.getTopN(), 0);
        if (topN == 0) {
            response.setItems(List.of());
            return response;
        }
        boolean similar = "similar".equalsIgnoreCase(request.getType());

        List<RecommendationItemDTO> merged = new ArrayList<>();
        Set<Long> picked = new LinkedHashSet<>();
        // ① AI 优先：任何异常都兜底回退规则，绝不 500（设计 §7「AI 失败回退规则」）
        List<RecommendationItemDTO> aiItems;
        try {
            aiItems = similar ? aiService.recommendSimilar(request) : aiService.recommend(request);
        } catch (Exception ex) {
            log.warn("AI 推荐异常，回退规则推荐: {}", ex.toString());
            aiItems = List.of();
        }
        for (RecommendationItemDTO item : aiItems) {
            if (item.getProductId() != null && picked.add(item.getProductId())) {
                merged.add(item);
            }
        }
        // ② 用规则推荐补足剩余名额（避免 AI 只给了 1~2 条时页面太空）；规则异常同样不 500
        if (merged.size() < topN) {
            try {
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
            } catch (Exception ex) {
                log.warn("规则推荐异常，返回当前已收集结果: {}", ex.toString());
            }
        }
        // ③ 统一按 topN 截断（防御 AI 返回超量，取前 topN 保持稳定顺序）
        if (merged.size() > topN) {
            merged = new ArrayList<>(merged.subList(0, topN));
        }
        response.setItems(merged);
        return response;
    }
}
