package com.example.mall.agentservice.repository;

import com.example.mall.agentservice.model.HealthProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 健康档案仓储（shopdb_agent.health_profile，UK user_id）。 */
public interface HealthProfileRepository extends JpaRepository<HealthProfile, Long> {

    Optional<HealthProfile> findByUserId(Long userId);
}
