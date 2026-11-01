package com.example.mall.agentservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 用户健康档案 / 长期记忆（shopdb_agent.health_profile）。一人一条（UK user_id），
 * 摘要由 HealthMemoryService 幂等覆盖更新，注入后续 Agent 上下文。
 */
@Entity
@Table(name = "health_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_profile_user", columnNames = "user_id"))
public class HealthProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 自然语言摘要（由事实卡派生，注入后续 Agent 上下文） */
    @Lob
    @Column(name = "profile_summary")
    private String profileSummary;

    /** L1 结构化事实卡（JSON）：合并写入语义，见 HealthFactCard；summary 由其派生 */
    @Lob
    @Column(name = "fact_card")
    private String factCard;

    /** L3 人物画像卡（JSON）：称呼/沟通风格/健康目标/偏好，见 UserPersonaCard */
    @Lob
    @Column(name = "persona_card")
    private String personaCard;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getProfileSummary() {
        return profileSummary;
    }

    public void setProfileSummary(String profileSummary) {
        this.profileSummary = profileSummary;
    }

    public String getFactCard() {
        return factCard;
    }

    public void setFactCard(String factCard) {
        this.factCard = factCard;
    }

    public String getPersonaCard() {
        return personaCard;
    }

    public void setPersonaCard(String personaCard) {
        this.personaCard = personaCard;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
