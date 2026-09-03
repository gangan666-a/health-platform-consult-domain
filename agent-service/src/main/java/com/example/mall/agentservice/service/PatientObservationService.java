package com.example.mall.agentservice.service;

import com.example.mall.agentservice.model.ReportIndicatorRecord;
import com.example.mall.agentservice.report.IndicatorNormalizer;
import com.example.mall.agentservice.repository.ReportIndicatorRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 患者观测图（Episode Graph，长期项 v1）：把「同用户跨多份报告的同一指标」在<b>查询时投影</b>为一条
 * 时间序列趋势——不做新存储（符合教程「物理落地不要一开始过度设计」），直接复用
 * report_indicator 的复合索引 IDX(user_id, indicator_name, exam_date)（落库名已是归一名，与对比查询同源）。
 *
 * <p>与 {@link ReportCompareService} 的区别：对比服务只并列展示原始多日期结果，本服务在此基础上
 * <b>派生趋势语义</b>——较上次的变化量、方向（改善/恶化/平稳）、最近一次正常日期，并输出可直接注入
 * 解读上下文的紧凑摘要。这是「图谱关联证据 + 规则判定」之外的第三个维度：<b>跨时间的个人化趋势</b>。
 *
 * <p>职责边界：本服务只做「观测投影 + 趋势描述」。<b>不判定临床显著性</b>
 * （显著性阈值待医学确认，沿用对比服务的纪律）；方向仅基于数值增减与异常方向推导，供解读参考而非诊断。
 */
@Service
public class PatientObservationService {

    private static final Logger log = LoggerFactory.getLogger(PatientObservationService.class);

    private static final Pattern LEADING_NUMBER = Pattern.compile("[-+]?\\d*\\.?\\d+");
    private static final int MAX_POINTS = 8;

    private final ReportIndicatorRecordRepository indicatorRepository;
    private final IndicatorNormalizer normalizer;

    public PatientObservationService(ReportIndicatorRecordRepository indicatorRepository,
                                     IndicatorNormalizer normalizer) {
        this.indicatorRepository = indicatorRepository;
        this.normalizer = normalizer;
    }

    /** 单个指标的时间序列趋势（查询时投影，不落新表）。 */
    public IndicatorTrend trend(Long userId, String indicatorRaw) {
        String canonical = normalizer.normalize(indicatorRaw == null ? "" : indicatorRaw);
        List<ReportIndicatorRecord> rows =
                indicatorRepository.findByUserIdAndIndicatorNameOrderByExamDateAsc(userId, canonical);
        return build(canonical, rows);
    }

    /** 多指标紧凑趋势段（注入报告解读上下文；空则回空串）。 */
    public String trendSection(Long userId, List<String> indicators) {
        if (userId == null || indicators == null || indicators.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String indicator : indicators) {
            try {
                IndicatorTrend t = trend(userId, indicator);
                if (t.examCount() >= 2) {
                    sb.append("- ").append(t.summary()).append('\n');
                }
            } catch (RuntimeException e) {
                log.debug("趋势投影跳过 {}: {}", indicator, e.getMessage());
            }
        }
        return sb.length() == 0 ? "" : sb.toString().stripTrailing();
    }

    private IndicatorTrend build(String canonical, List<ReportIndicatorRecord> rows) {
        if (rows.isEmpty()) {
            return new IndicatorTrend(canonical, 0, null, null, List.of(),
                    Direction.UNKNOWN, null, null, null, "无该指标的历史数据");
        }
        List<Point> points = new ArrayList<>();
        for (ReportIndicatorRecord r : rows) {
            points.add(new Point(r.getExamDate(), r.getValue(), r.getUnit(),
                    r.getStatus() == null ? "UNKNOWN" : r.getStatus()));
        }
        ReportIndicatorRecord latest = rows.get(rows.size() - 1);
        ReportIndicatorRecord previous = rows.size() >= 2 ? rows.get(rows.size() - 2) : null;
        String unit = latest.getUnit();

        Direction direction = Direction.STABLE;
        Double delta = null;
        if (previous != null) {
            Double lv = parseNumber(latest.getValue());
            Double pv = parseNumber(previous.getValue());
            if (lv != null && pv != null) {
                delta = round(lv - pv);
                direction = deriveDirection(lv, pv, latest.getStatus());
            }
        }
        LocalDate lastNormal = null;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if ("NORMAL".equalsIgnoreCase(rows.get(i).getStatus())) {
                lastNormal = rows.get(i).getExamDate();
                break;
            }
        }
        List<Point> windowed = points.size() > MAX_POINTS
                ? points.subList(points.size() - MAX_POINTS, points.size()) : points;
        String summary = summarize(canonical, windowed, delta, direction, lastNormal);
        return new IndicatorTrend(canonical, rows.size(), latest.getValue(), latest.getStatus(),
                List.copyOf(windowed), direction, unit, latest.getExamDate(), lastNormal, summary);
    }

    private Direction deriveDirection(double latest, double previous, String status) {
        if (latest == previous) {
            return Direction.STABLE;
        }
        boolean rising = latest > previous;
        if ("HIGH".equalsIgnoreCase(status)) {
            return rising ? Direction.WORSENING : Direction.IMPROVING; // 偏高：降=改善，升=恶化
        }
        if ("LOW".equalsIgnoreCase(status)) {
            return rising ? Direction.IMPROVING : Direction.WORSENING; // 偏低：升=改善，降=恶化
        }
        return rising ? Direction.RISING : Direction.FALLING;
    }

    private String summarize(String canonical, List<Point> points, Double delta,
                             Direction direction, LocalDate lastNormal) {
        if (points.size() < 2) {
            Point only = points.get(0);
            return String.format("%s：仅 1 次记录（%s%s，%s），暂无法判断趋势",
                    canonical, only.value(), blank(only.unit()), only.status());
        }
        Point latest = points.get(points.size() - 1);
        Point previous = points.get(points.size() - 2);
        StringBuilder sb = new StringBuilder(canonical).append("：");
        if (delta != null) {
            sb.append(String.format("较上次 %s%s → %s%s（%s%s）",
                    previous.value(), blank(previous.unit()),
                    latest.value(), blank(latest.unit()),
                    delta > 0 ? "+" : "", delta));
        } else {
            sb.append(String.format("上次 %s → 本次 %s", previous.value(), latest.value()));
        }
        sb.append("，").append(direction.label());
        sb.append(lastNormal != null ? "；最近正常 " + lastNormal : "；近期无正常记录");
        return sb.toString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? "" : " " + s;
    }

    private static Double parseNumber(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        Matcher m = LEADING_NUMBER.matcher(s.trim());
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** 趋势方向（仅描述，不替代临床判定）。 */
    public enum Direction {
        IMPROVING("改善"), WORSENING("恶化"), STABLE("平稳"),
        RISING("上升"), FALLING("下降"), UNKNOWN("未知");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 单个时间点。 */
    public record Point(LocalDate examDate, String value, String unit, String status) {
    }

    /** 指标趋势投影结果。 */
    public record IndicatorTrend(String indicator, int examCount, String latestValue, String latestStatus,
                                 List<Point> points, Direction direction, String unit,
                                 LocalDate latestDate, LocalDate lastNormalDate, String summary) {
    }
}
