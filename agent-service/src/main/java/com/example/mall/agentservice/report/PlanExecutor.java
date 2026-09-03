package com.example.mall.agentservice.report;

import com.example.mall.agentservice.config.ReportMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 计划执行器（plan-execute 的 EXECUTE 步骤）：
 * ① CLASSIFY_PAGES——页级分类（虚拟线程并行，按页序聚合保证确定性）；
 * ② EXTRACT_PAGES——按计划通道路由抽取（TEXT_LLM → LlmTextPageExtractor，
 *    VISION → VisionAnalyzer），单页异常降级为「未识别」空抽取不阻断整批；
 * ③ AGGREGATE——PageAggregator 归并统一模型；④ VALIDATE——校验备注入轨迹。
 * 页级并行是本次解析的主要提速手段（页间无依赖），LLM 网关限流保证不超配额。
 */
@Component
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final PageClassifier classifier;
    private final LlmTextPageExtractor textExtractor;
    private final VisionAnalyzer visionAnalyzer;
    private final PageTypeRegistry registry;
    private final PageAggregator aggregator;
    private final ReportMetrics metrics;

    public PlanExecutor(PageClassifier classifier, LlmTextPageExtractor textExtractor,
                        VisionAnalyzer visionAnalyzer, PageTypeRegistry registry, PageAggregator aggregator,
                        ReportMetrics metrics) {
        this.classifier = classifier;
        this.textExtractor = textExtractor;
        this.visionAnalyzer = visionAnalyzer;
        this.registry = registry;
        this.aggregator = aggregator;
        this.metrics = metrics;
    }

    public PageAggregator.AggregatedReport execute(FileProfile profile, ReportPlan plan,
                                                   PlanExecutionTrace trace, ProgressListener progress) {
        List<PageContent> pages = profile.pages();
        if (pages.isEmpty()) {
            trace.skipped(ReportPlan.STEP_CLASSIFY, "无有效页面");
            trace.skipped(ReportPlan.STEP_EXTRACT, "无有效页面");
            trace.skipped(ReportPlan.STEP_AGGREGATE, "无有效页面");
            trace.skipped(ReportPlan.STEP_VALIDATE, "无有效页面");
            return aggregator.aggregate(profile, List.of());
        }

        // ① 页级分类（并行、按页序收集）
        trace.running(ReportPlan.STEP_CLASSIFY, pages.size() + "页");
        Map<Integer, PageType> pageTypes = classifyPages(pages, profile.fileName(), progress);
        trace.done(ReportPlan.STEP_CLASSIFY, pageTypes.values().stream()
                .map(PageType::name).collect(Collectors.joining(",")));

        // ② 按通道抽取（并行、按页序收集）
        trace.running(ReportPlan.STEP_EXTRACT, pages.size() + "页");
        List<PageExtraction> extractions = extractPages(pages, plan, pageTypes, profile.fileName(), trace, progress);
        trace.done(ReportPlan.STEP_EXTRACT, extractions.stream()
                .map(e -> "第" + e.pageNo() + "页·" + e.pageType().name() + "·" + e.channel())
                .collect(Collectors.joining("，")));

        // ③ 聚合
        trace.running(ReportPlan.STEP_AGGREGATE, "");
        PageAggregator.AggregatedReport aggregated = aggregator.aggregate(profile, extractions);
        trace.done(ReportPlan.STEP_AGGREGATE, aggregated.parsed().indicators().size() + "指标·"
                + aggregated.parsed().findings().size() + "所见·" + aggregated.type());

        // ④ 校验
        trace.running(ReportPlan.STEP_VALIDATE, "");
        if (aggregated.notes().isEmpty()) {
            trace.done(ReportPlan.STEP_VALIDATE, "通过");
        } else {
            trace.done(ReportPlan.STEP_VALIDATE, String.join("；", aggregated.notes()));
        }
        crossCheckExpectedPageTypes(plan, pageTypes, trace);
        return aggregated;
    }

    /** 页级并行分类；单页异常 → OTHER 兜底（不阻断）。任务包裹 MDC 上下文，traceId 贯穿虚拟线程。 */
    private Map<Integer, PageType> classifyPages(List<PageContent> pages, String fileName,
                                                 ProgressListener progress) {
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PageType>> futures = pages.stream()
                    .map(page -> pool.submit(withMdc(() ->
                            classifyOne(page, fileName, done, pages.size(), progress))))
                    .toList();
            Map<Integer, PageType> result = new ConcurrentHashMap<>();
            for (int i = 0; i < pages.size(); i++) {
                try {
                    PageType type = futures.get(i).get();
                    result.put(pages.get(i).pageNo(), type);
                    metrics.pageType(type.name());
                } catch (Exception e) {
                    log.warn("第{}页分类异常，按 OTHER 处理: {}", pages.get(i).pageNo(), e.getMessage());
                    result.put(pages.get(i).pageNo(), PageType.OTHER);
                }
            }
            return result;
        }
    }

    private PageType classifyOne(PageContent page, String fileName, AtomicInteger done,
                                 int total, ProgressListener progress) {
        try {
            return classifier.classify(page, fileName);
        } finally {
            progress.onProgress("CLASSIFY", done.incrementAndGet(), total,
                    "第 " + done.get() + "/" + total + " 页分类完成");
        }
    }

    /** 页级并行抽取：TEXT_LLM → 文本抽取器；VISION → 视觉通道（配方的页型供未来 VLM 使用）。 */
    private List<PageExtraction> extractPages(List<PageContent> pages, ReportPlan plan,
                                               Map<Integer, PageType> pageTypes, String fileName,
                                               PlanExecutionTrace trace, ProgressListener progress) {
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PageExtraction>> futures = pages.stream()
                    .map(page -> pool.submit(withMdc(() ->
                            extractOne(page, plan, pageTypes, fileName, done, pages.size(), progress))))
                    .toList();
            List<PageExtraction> result = new ArrayList<>(pages.size());
            for (int i = 0; i < pages.size(); i++) {
                try {
                    PageExtraction extraction = futures.get(i).get();
                    result.add(extraction);
                    metrics.pageChannel(extraction.channel());
                } catch (Exception e) {
                    PageContent page = pages.get(i);
                    log.warn("第{}页抽取异常，降级未识别空抽取: {}", page.pageNo(), e.getMessage());
                    trace.failed(ReportPlan.STEP_EXTRACT + "#P" + page.pageNo(), e.getMessage());
                    result.add(new PageExtraction(page.pageNo(), PageType.OTHER, PageType.OTHER.display(),
                            null, List.of(), List.of(), PageExtraction.CHANNEL_VISION_UNAVAILABLE));
                }
            }
            return result;
        }
    }

    private PageExtraction extractOne(PageContent page, ReportPlan plan, Map<Integer, PageType> pageTypes,
                                      String fileName, AtomicInteger done, int total, ProgressListener progress) {
        PageType type = pageTypes.getOrDefault(page.pageNo(), PageType.OTHER);
        try {
            PageChannel channel = plan.channelOf(page);
            return channel == PageChannel.VISION
                    ? visionAnalyzer.analyze(page, registry.recipe(type))
                    : textExtractor.extract(page, type);
        } finally {
            progress.onProgress("EXTRACT", done.incrementAndGet(), total,
                    "第 " + done.get() + "/" + total + " 页抽取完成");
        }
    }

    /** MDC 上下文随任务进入虚拟线程（traceId 贯穿页级并行日志），退出时还原。 */
    private static <T> Callable<T> withMdc(Callable<T> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            }
            try {
                return task.call();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /** 计划预期页型 vs 实际分类交叉核验（偏差记轨迹，不判失败——分类器兜底语义允许偏差）。 */
    private static void crossCheckExpectedPageTypes(ReportPlan plan, Map<Integer, PageType> actual,
                                                    PlanExecutionTrace trace) {
        if (plan.expectedPageTypes().isEmpty()) {
            return;
        }
        List<String> unexpected = actual.values().stream()
                .map(Enum::name)
                .filter(t -> !plan.expectedPageTypes().contains(t) && !"OTHER".equals(t))
                .distinct()
                .toList();
        if (!unexpected.isEmpty()) {
            trace.done(ReportPlan.STEP_VALIDATE, "计划外页型（已按实际页型处理）: " + String.join(",", unexpected));
        }
    }
}
