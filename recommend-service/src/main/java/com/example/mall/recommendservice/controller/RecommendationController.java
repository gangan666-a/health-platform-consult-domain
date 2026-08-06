package com.example.mall.recommendservice.controller;

import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationResponse;
import com.example.mall.recommendservice.service.RecommendationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 智能导购 REST API
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /** 根据已购商品 + 全量目录生成推荐（order-service 在支付成功后调用） */
    @PostMapping
    public RecommendationResponse recommend(@Valid @RequestBody RecommendRequest request) {
        return recommendationService.recommend(request);
    }
}
