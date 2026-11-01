package com.example.mall.agentservice.orchestration.tools;

import com.example.mall.agentservice.service.HealthMemoryService;
import com.example.mall.agentservice.service.HealthFactCard;
import com.example.mall.agentservice.service.UserPersonaCard;
import com.example.mall.agentservice.service.UserPersonaService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 记忆写入工具（L1 观点 + L3 人物画像）：问诊/问药 Agent 在用户明确陈述时由模型调用——
 * 与 HealthFactCard 的写入纪律一致（只落用户明确表达的内容，不做推测性抽取）。
 * 绑定对象持有 userId，工具权限 = 本人数据（R-6，与 ReportTools 相同模式）。
 */
@Component
public class ProfileMemoryTools {

    private final HealthMemoryService healthMemory;
    private final UserPersonaService personaService;

    public ProfileMemoryTools(HealthMemoryService healthMemory, UserPersonaService personaService) {
        this.healthMemory = healthMemory;
        this.personaService = personaService;
    }

    public ProfileMemoryToolBinding bind(Long userId) {
        return new ProfileMemoryToolBinding(userId);
    }

    public class ProfileMemoryToolBinding {

        private final Long userId;

        ProfileMemoryToolBinding(Long userId) {
            this.userId = userId;
        }

        @Tool(description = "记录用户明确表达的主观观点/偏好（如「怕苦不想吃片剂」「觉得食疗比吃药好」）。"
                + "仅限用户原话明确表达时调用，不要推测。同主题再次表达会覆盖旧观点")
        public String recordOpinion(
                @ToolParam(description = "观点主题，如「剂型偏好」「治疗方式偏好」") String topic,
                @ToolParam(description = "用户原话表达的观点内容") String content) {
            if (userId == null) {
                return "暂无用户身份，无法记录观点";
            }
            if (topic == null || topic.isBlank() || content == null || content.isBlank()) {
                return "观点主题与内容不能为空";
            }
            healthMemory.recordFact(userId, HealthFactCard.FactType.OPINION, topic.trim(), content.trim());
            return "已记录你的观点：" + topic.trim();
        }

        @Tool(description = "更新用户画像（个性化沟通用）：希望被怎么称呼、沟通风格偏好、健康目标、其它偏好。"
                + "仅限用户明确表达时调用；不需要修改的参数传空")
        public String updatePersona(
                @ToolParam(description = "称呼，如「老张」，不修改传空") String nickname,
                @ToolParam(description = "沟通风格，如「简洁要点式」「喜欢解释原理」，不修改传空") String commStyle,
                @ToolParam(description = "健康目标，多个用逗号分隔，如「控糖,减重」，不修改传空") String healthGoals,
                @ToolParam(description = "其它偏好，多个用逗号分隔，如「怕苦,倾向外用药」，不修改传空") String preferences) {
            if (userId == null) {
                return "暂无用户身份，无法更新画像";
            }
            UserPersonaCard patch = new UserPersonaCard();
            patch.setNickname(trimToNull(nickname));
            patch.setCommStyle(trimToNull(commStyle));
            patch.setHealthGoals(splitCsv(healthGoals));
            patch.setPreferences(splitCsv(preferences));
            if (patch.isEmpty()) {
                return "未提供需要更新的画像内容";
            }
            personaService.upsertPersona(userId, patch);
            return "已更新你的偏好画像";
        }

        private String trimToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private List<String> splitCsv(String csv) {
            if (csv == null || csv.isBlank()) {
                return new ArrayList<>();
            }
            return Arrays.stream(csv.split("[,，、]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }
}
