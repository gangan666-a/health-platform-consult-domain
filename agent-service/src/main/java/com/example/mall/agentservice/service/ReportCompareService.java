package com.example.mall.agentservice.service;

import com.example.mall.agentservice.dto.ReportCompareResponse;
import com.example.mall.agentservice.model.ReportIndicatorRecord;
import com.example.mall.agentservice.report.IndicatorNormalizer;
import com.example.mall.agentservice.repository.ReportIndicatorRecordRepository;
import com.example.mall.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 同指标多日期对比（PM-DESIGN·6，OPEN-5 简化）：并列展示 + 咨询提示，不做显著性判定。
 * 数据边界 = userId（鉴权由上游/BFF 承担，服务端以 userId 为隔离边界，fail-closed）。
 * 入参指标名经同义词归一（查「白细胞」能命中库里的「WBC」），落库名已是归一名。
 */
@Service
public class ReportCompareService {

    private static final String NOTE =
            "以下为该指标多日期结果并列展示；变化是否显著需线下医生评估（显著性阈值待医学确认，本期不做判定）。";

    private final ReportIndicatorRecordRepository indicatorRepository;
    private final IndicatorNormalizer normalizer;

    public ReportCompareService(ReportIndicatorRecordRepository indicatorRepository,
                                IndicatorNormalizer normalizer) {
        this.indicatorRepository = indicatorRepository;
        this.normalizer = normalizer;
    }

    public ReportCompareResponse compare(Long userId, String indicatorName) {
        if (indicatorName == null || indicatorName.isBlank()) {
            throw new BusinessException("对比指标不能为空");
        }
        String canonical = normalizer.normalize(indicatorName);
        List<ReportIndicatorRecord> rows =
                indicatorRepository.findByUserIdAndIndicatorNameOrderByExamDateAsc(userId, canonical);
        if (rows.isEmpty()) {
            throw new BusinessException("无该指标的历史数据");
        }
        List<ReportCompareResponse.CompareEntry> entries = rows.stream()
                .map(r -> new ReportCompareResponse.CompareEntry(
                        r.getExamDate(), r.getValue(), r.getUnit(), r.getRefRange(), r.getStatus()))
                .toList();
        return new ReportCompareResponse(canonical, entries, NOTE);
    }
}
