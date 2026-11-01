package com.example.mall.agentservice.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * L3 人物画像卡（长期记忆的人物维度）：称呼 / 沟通风格 / 健康目标 / 主观偏好，
 * JSON 存 health_profile.persona_card。与 L1 事实卡的边界——画像描述「用户是什么样的人」，
 * 供各 Agent 个性化沟通；不承载医学事实（那是 L1）。
 * 合并语义（merge）：标量非空覆盖、列表去重追加（超上限滚动保留最近），
 * 只由用户明确表达时经 ProfileMemoryTools 写入。
 * 注意：getter 采用标准 JavaBean 命名（getX），Jackson 原生识别，规避 HealthFactCard 中
 * facts() 命名导致的静默丢字段问题。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserPersonaCard {

    private static final int MAX_LIST_ITEMS = 10;

    /** 用户希望被称呼的方式（如「老张」「小王」），空表示未采集 */
    private String nickname;

    /** 沟通风格偏好（如「简洁要点式」「喜欢解释原理」） */
    private String commStyle;

    /** 健康目标（如「控糖」「减重」「戒酒」），去重追加，保最近 10 条 */
    private List<String> healthGoals = new ArrayList<>();

    /** 其它主观偏好（如「怕苦，倾向外用药」「不想输液」），去重追加，保最近 10 条 */
    private List<String> preferences = new ArrayList<>();

    private LocalDate updatedAt;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getCommStyle() {
        return commStyle;
    }

    public void setCommStyle(String commStyle) {
        this.commStyle = commStyle;
    }

    public List<String> getHealthGoals() {
        return healthGoals;
    }

    public void setHealthGoals(List<String> healthGoals) {
        this.healthGoals = healthGoals == null ? new ArrayList<>() : new ArrayList<>(healthGoals);
    }

    public List<String> getPreferences() {
        return preferences;
    }

    public void setPreferences(List<String> preferences) {
        this.preferences = preferences == null ? new ArrayList<>() : new ArrayList<>(preferences);
    }

    public LocalDate getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDate updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 增量合并：标量非空覆盖（后写为准），列表去重追加、超限滚动保留最近。 */
    public UserPersonaCard merge(UserPersonaCard patch, LocalDate today) {
        if (patch == null) {
            return this;
        }
        if (patch.nickname != null && !patch.nickname.isBlank()) {
            this.nickname = patch.nickname.trim();
        }
        if (patch.commStyle != null && !patch.commStyle.isBlank()) {
            this.commStyle = patch.commStyle.trim();
        }
        this.healthGoals = mergeList(this.healthGoals, patch.healthGoals);
        this.preferences = mergeList(this.preferences, patch.preferences);
        this.updatedAt = today;
        return this;
    }

    /** 是否全空（未采集任何画像信息）。@JsonIgnore：Jackson 会把 isEmpty() 当作 empty 属性序列化，往返即报未知字段。 */
    @JsonIgnore
    public boolean isEmpty() {
        return blank(nickname) && blank(commStyle)
                && healthGoals.stream().allMatch(UserPersonaCard::blank)
                && preferences.stream().allMatch(UserPersonaCard::blank);
    }

    /** 派生注入段（供 system prompt 使用）；全空返回空串（调用方按非空追加）。 */
    public String deriveSection() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【用户画像（个性化沟通参考，非医学事实）】");
        if (!blank(nickname)) {
            sb.append("\n称呼：").append(nickname);
        }
        if (!blank(commStyle)) {
            sb.append("\n沟通风格：").append(commStyle);
        }
        String goals = joinNonBlank(healthGoals);
        if (!goals.isEmpty()) {
            sb.append("\n健康目标：").append(goals);
        }
        String prefs = joinNonBlank(preferences);
        if (!prefs.isEmpty()) {
            sb.append("\n偏好：").append(prefs);
        }
        return sb.toString();
    }

    private static List<String> mergeList(List<String> current, List<String> incoming) {
        List<String> merged = new ArrayList<>(current);
        for (String item : incoming == null ? List.<String>of() : incoming) {
            if (blank(item)) {
                continue;
            }
            String trimmed = item.trim();
            if (!merged.contains(trimmed)) {
                merged.add(trimmed);
            }
        }
        return merged.size() > MAX_LIST_ITEMS
                ? new ArrayList<>(merged.subList(merged.size() - MAX_LIST_ITEMS, merged.size()))
                : merged;
    }

    private static String joinNonBlank(List<String> items) {
        return items.stream().filter(item -> !blank(item))
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
