package com.example.mall.agentservice.orchestration.tools;

import com.example.mall.agentservice.dto.ReportCompareResponse;
import com.example.mall.agentservice.service.HealthMemoryService;
import com.example.mall.agentservice.service.PatientObservationService;
import com.example.mall.agentservice.service.ReportCompareService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 报告解读 Agent 专用工具：同指标历史对比 + 跨报告趋势投影 + 健康档案（长期记忆）。
 * 绑定对象持有 userId，工具权限 = 本人数据（R-6）。
 */
@Component
public class ReportTools {

    private final ReportCompareService compareService;
    private final HealthMemoryService healthMemory;
    private final PatientObservationService observationService;

    public ReportTools(ReportCompareService compareService, HealthMemoryService healthMemory,
                       PatientObservationService observationService) {
        this.compareService = compareService;
        this.healthMemory = healthMemory;
        this.observationService = observationService;
    }

    public ReportToolBinding bind(Long userId) {
        return new ReportToolBinding(userId);
    }

    public class ReportToolBinding {

        private final Long userId;

        ReportToolBinding(Long userId) {
            this.userId = userId;
        }

        @Tool(description = "查询用户指定指标的历史检查结果（多日期并列），用于同指标对比解读")
        public String queryReportCompare(@ToolParam(description = "指标名，如 WBC") String indicatorName) {
            if (userId == null) {
                return "暂无用户身份，无法查询历史对比";
            }
            try {
                return format(compareService.compare(userId, indicatorName));
            } catch (RuntimeException e) {
                return e.getMessage();
            }
        }

        @Tool(description = "获取用户健康档案摘要（长期记忆），了解既往检查与关注点")
        public String getHealthProfile() {
            if (userId == null) {
                return "暂无用户身份，无法读取健康档案";
            }
            String profile = healthMemory.getProfile(userId);
            return profile.isBlank() ? "暂无健康档案记录" : profile;
        }

        @Tool(description = "查询用户指定指标的跨报告趋势（变化量/方向/最近正常日期），用于判断指标是在改善还是恶化")
        public String queryIndicatorTrend(@ToolParam(description = "指标名，如 LDL") String indicatorName) {
            if (userId == null) {
                return "暂无用户身份，无法查询趋势";
            }
            try {
                return observationService.trend(userId, indicatorName).summary();
            } catch (RuntimeException e) {
                return e.getMessage();
            }
        }

        private String format(ReportCompareResponse resp) {
            StringBuilder sb = new StringBuilder();
            sb.append("指标 ").append(resp.indicatorName()).append(" 历史结果：\n");
            for (ReportCompareResponse.CompareEntry e : resp.entries()) {
                sb.append("- ").append(e.examDate()).append(": ")
                        .append(e.value()).append(' ').append(nullToDash(e.unit()))
                        .append("，参考范围 ").append(nullToDash(e.refRange()))
                        .append("，结果 ").append(nullToDash(e.status())).append('\n');
            }
            return sb.toString();
        }

        private static String nullToDash(String s) {
            return s == null || s.isBlank() ? "-" : s;
        }
    }
}
