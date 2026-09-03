package com.example.mall.agentservice.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 评测门禁支撑（防线4）：金标样例 = resources/report/eval/*.json（机构格式各异的真实样式脱敏改写），
 * 指标按「归一名+数值」精确匹配算字段级 recall/precision（precision 惩罚幻觉指标），
 * 检查所见按关键词命中算 findingRecall。确定性门禁 CI 必跑；LLM 门禁需真实 Key。
 */
public abstract class ReportEvalSupport {

    protected record ExpectedIndicator(String name, String value) {
    }

    protected record Expected(String examName, List<ExpectedIndicator> indicators, List<String> findingKeywords) {
    }

    protected record Fixture(String name, String fileName, List<String> pages, Expected expected) {
    }

    /** 一份样例的评测结果（toString 供失败信息定位）。 */
    protected record EvalResult(double recall, double precision, double findingRecall, String examName,
                                List<String> actualIndicators) {
        @Override
        public String toString() {
            return "recall=" + recall + ", precision=" + precision + ", findingRecall=" + findingRecall
                    + ", examName=" + examName + ", actual=" + actualIndicators;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final IndicatorNormalizer normalizer = new IndicatorNormalizer(mapper);

    /** 子类提供被评测的解析器（确定性组件拼装 / Spring 上下文 LLM 全家桶）。 */
    protected abstract ReportParser parser();

    protected List<Fixture> loadFixtures() throws IOException {
        Path dir = Path.of(new ClassPathResource("report/eval").getFile().toURI());
        try (Stream<Path> files = Files.list(dir)) {
            List<Fixture> fixtures = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".json")).sorted()
                    .forEach(p -> {
                        try {
                            fixtures.add(mapper.readValue(p.toFile(), Fixture.class));
                        } catch (IOException e) {
                            throw new IllegalStateException("评测样例不可读: " + p, e);
                        }
                    });
            return fixtures;
        }
    }

    protected EvalResult evaluate(Fixture fixture) {
        List<PageContent> pages = new ArrayList<>();
        for (int i = 0; i < fixture.pages().size(); i++) {
            pages.add(new PageContent(i + 1, fixture.pages().get(i), null));
        }
        FileProfile profile = new FileProfile(fixture.fileName(), "application/pdf", true, pages, null);
        ReportParseResult result = parser().parseProfile(profile, ProgressListener.noop());

        Set<String> expectedKeys = new HashSet<>();
        for (ExpectedIndicator indicator : fixture.expected().indicators()) {
            expectedKeys.add(key(normalizer.normalize(indicator.name()), indicator.value()));
        }
        Set<String> actualKeys = new HashSet<>();
        List<String> actualNames = new ArrayList<>();
        for (var in : result.doc().indicators()) {
            actualKeys.add(key(in.name(), in.value()));
            actualNames.add(in.name() + "=" + in.value());
        }
        long matched = expectedKeys.stream().filter(actualKeys::contains).count();
        double recall = expectedKeys.isEmpty() ? 1.0 : (double) matched / expectedKeys.size();
        double precision = actualKeys.isEmpty() ? 1.0 : (double) matched / actualKeys.size();

        long findingHit = fixture.expected().findingKeywords().stream()
                .filter(k -> result.findings().stream().anyMatch(f -> f.text().contains(k)))
                .count();
        double findingRecall = fixture.expected().findingKeywords().isEmpty() ? 1.0
                : (double) findingHit / fixture.expected().findingKeywords().size();

        return new EvalResult(recall, precision, findingRecall, result.doc().examName(), actualNames);
    }

    private static String key(String name, String value) {
        return name + "=" + value;
    }
}
