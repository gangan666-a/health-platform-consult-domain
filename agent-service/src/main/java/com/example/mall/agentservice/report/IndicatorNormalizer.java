package com.example.mall.agentservice.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 指标同义词归一 + 受控编码（spec OPEN-3 / P1-2）：不同机构对同一指标用中文名/缩写/带单位尾缀的不同写法，
 * 归一到统一标准名后，跨机构历史对比、ES 参考范围与知识检索才可命中。
 * 词典 resources/report/indicator-aliases.json 随代码版本化；未命中保留原名（fail-open，不猜）。
 * 构造器加载（非 @PostConstruct）：便于单测直接 new。
 *
 * <p><b>P1-2 受控编码</b>：除标准名外，为每个概念提供 LOINC 编码（{@link #codeOf}）与
 * 统一概念标识（{@link #conceptId}，形如 {@code LOINC:718-7}）。
 * 为什么需要：标准名仍是「本系统内部的字符串约定」，换一家机构、换一套 LIS 就可能不认；
 * LOINC 是跨机构可交换的受控编码，是跨院趋势合并与外部知识对接的真正主键。
 * <b>未核实编码一律留 null</b>——{@code conceptId} 回退为 {@code LOCAL:<标准名>}，
 * 宁可显式地「未编码」，也不填一个看起来对但没核实的码（错码比缺码危险得多，它会让两份不同指标被静默合并）。
 */
@Component
public class IndicatorNormalizer {

    private static final Logger log = LoggerFactory.getLogger(IndicatorNormalizer.class);

    private static final String ALIASES_PATH = "report/indicator-aliases.json";
    /** 去尾部括注单位（如「白细胞计数(10^9/L)」→「白细胞计数」）与空白。 */
    private static final Pattern TRAILING_UNIT = Pattern.compile("\\s*[（(][^（）()]*[)）]\\s*$");

    /** 未挂 LOINC 时的概念标识前缀：显式区分「已编码」与「仅本系统内部约定」。 */
    public static final String LOCAL_CODE_SYSTEM = "LOCAL";

    /** 词条典条目：标准名 + LOINC 编码（可空）+ 别名列表（JSON 反序列化用）。 */
    public record AliasEntry(String canonical, String loinc, List<String> aliases) {
    }

    private record AliasFile(List<AliasEntry> aliases) {
    }

    private final ObjectMapper objectMapper;
    private final Map<String, String> lookup = new HashMap<>();
    /** 标准名 → LOINC 编码（仅收录已核实条目）。 */
    private final Map<String, String> codeByCanonical = new HashMap<>();

    public IndicatorNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream in = new ClassPathResource(ALIASES_PATH).getInputStream()) {
            // 容忍 _comment 等未知字段：词典由医学运营手动维护，允许注释；
            // 用 copy() 避免改动调用方共享的 ObjectMapper 配置（如 Spring 注入的实例）
            ObjectMapper reader = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            AliasFile file = reader.readValue(in, AliasFile.class);
            if (file.aliases() != null) {
                for (AliasEntry entry : file.aliases()) {
                    if (entry.canonical() == null || entry.canonical().isBlank()) {
                        continue;
                    }
                    String canonical = entry.canonical().trim();
                    lookup.putIfAbsent(normalizeKey(canonical), canonical);
                    if (entry.loinc() != null && !entry.loinc().isBlank()) {
                        codeByCanonical.put(canonical, entry.loinc().trim());
                    }
                    if (entry.aliases() != null) {
                        for (String alias : entry.aliases()) {
                            if (alias != null && !alias.isBlank()) {
                                lookup.putIfAbsent(normalizeKey(alias), canonical);
                            }
                        }
                    }
                }
            }
            log.info("指标同义词典加载完成：{} 个查找键，{} 个标准名已挂 LOINC 编码",
                    lookup.size(), codeByCanonical.size());
        } catch (IOException e) {
            // 词典缺失属部署错误：宁可启动失败也不静默地全靠原名（那样历史对比会悄悄失准）
            throw new UncheckedIOException("指标同义词典缺失或不可读: " + ALIASES_PATH, e);
        }
    }

    /** 归一到标准名；未命中返回清洗（trim/去单位尾缀）后的原名，绝不返回空。 */
    public String normalize(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String cleaned = clean(name);
        String canonical = lookup.get(normalizeKey(cleaned));
        return canonical != null ? canonical : cleaned;
    }

    /**
     * 受控编码：归一后的 LOINC 码；未收录返回 {@code null}。
     * 调用方应把 null 当作「该概念尚未编码」处理，不要臆造默认值。
     */
    public String codeOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return codeByCanonical.get(normalize(name));
    }

    /**
     * 统一概念标识：{@code LOINC:<code>}，未编码时回退 {@code LOCAL:<标准名>}。
     * 用途：跨机构事实合并的主键（{@code HealthFactCard}）与外部知识对接。
     */
    public String conceptId(String name) {
        String canonical = normalize(name);
        if (canonical == null || canonical.isBlank()) {
            return null;
        }
        String code = codeByCanonical.get(canonical);
        return code == null ? LOCAL_CODE_SYSTEM + ":" + canonical : "LOINC:" + code;
    }

    /** 已挂 LOINC 的标准名数量（用于启动日志与治理观测）。 */
    public int codedConceptCount() {
        return codeByCanonical.size();
    }

    /** 去尾部单位尾缀并 trim；清洗后为空则返回原串 trim。 */
    private static String clean(String name) {
        String cleaned = TRAILING_UNIT.matcher(name.trim()).replaceAll("").trim();
        return cleaned.isEmpty() ? name.trim() : cleaned;
    }

    /** 查找键：全角→半角、小写、去空白。 */
    private static String normalizeKey(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '！' && c <= '～') {
                c -= 0xFEE0; // 全角 ASCII 区 → 半角
            }
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
