package com.example.mall.agentservice.graph;

import java.util.List;

/**
 * 图查询端口：多指标异常 → 可疑疾病推理（证据包形式）。
 *
 * <p><b>P0-3 证据包协议</b>：不再只返回「支持方向」的命中列表，而是每次推理给出三路证据——
 * <ul>
 *   <li>{@link EvidencePack#supporting()}：支持该疾病的异常指标；</li>
 *   <li>{@link EvidencePack#contradicting()}：反向证据（削弱/排除该疾病的指标与理由）；</li>
 *   <li>{@link EvidencePack#missing()}：缺失上下文（鉴别还需补做、但本次报告未检测的指标）。</li>
 * </ul>
 * 三路齐备，LLM 才能输出「更像 X，但也可能是 Y，还需要 Z 才能区分」的诚实表述，
 * 而不是把「指标 A 连指标 B 再连疾病」当成诊断结论。
 *
 * <p><b>P0-2 出处</b>：每条支持/反向证据都带 {@link Provenance}（来源 + 证据等级 + 知识版本），
 * 让解读可溯源、可对可信度分级。
 */
public interface GraphQueryPort {

    /** 异常信号：归一指标名 + 方向（HIGH/LOW）。 */
    record IndicatorSignal(String indicator, String direction) {
    }

    /** 一条知识的出处与证据等级（P0-2）：来源文献 / 指南 + 等级 A/B/C + 知识版本。 */
    record Provenance(String source, String evidenceGrade, String version) {

        /** 渲染为「来源(等级@版本)」；等级 C 追加待审核提示，避免占位知识被当作结论引用。 */
        public String render() {
            String grade = evidenceGrade == null || evidenceGrade.isBlank() ? "C" : evidenceGrade;
            String suffix = "C".equalsIgnoreCase(grade) ? "，待医学审核" : "";
            return source + "(" + grade + "@" + version + ")" + suffix;
        }
    }

    /** 一条证据：指标 + 方向 + 出处。 */
    record Evidence(String indicator, String direction, Provenance provenance) {

        public String render() {
            String arrow = "HIGH".equalsIgnoreCase(direction) ? "↑" : "↓";
            return indicator + arrow + "[" + provenance.render() + "]";
        }
    }

    /** 缺失上下文（鉴别还需的指标，本次报告未检测）。 */
    record MissingContext(String indicator, String reason) {
    }

    /**
     * 证据包（P0-3）：疾病 + 就诊科室 + 支持证据 + 反向证据 + 缺失上下文 + 典型症状。
     *
     * @param hits          命中的异常指标数（多指标联合定位的核心排序依据）
     * @param supporting    支持证据（指标异常方向 + 出处）
     * @param contradicting 反向证据（削弱/排除；可为空）
     * @param missing       缺失上下文（本次未检测但鉴别需要的指标；可为空）
     * @param symptoms      典型症状（一跳扩展的就诊引导素材）
     */
    record EvidencePack(String disease, String department, long hits,
                        List<Evidence> supporting, List<Evidence> contradicting,
                        List<MissingContext> missing, List<String> symptoms) {
    }

    /** 图库可用性（连接探活；不可用由调用方降级关系查表）。 */
    boolean available();

    /**
     * 多指标异常 → 有序证据包。
     *
     * @param signals    异常信号（归一名 + HIGH/LOW）
     * @param observed   本次报告<b>全部已检测</b>的归一名（含正常项），用于判定「缺失上下文」——
     *                   只在指标未检测时提示，已检测则不提示
     * @param limit      返回条数上限
     */
    List<EvidencePack> inferWithEvidence(List<IndicatorSignal> signals, List<String> observed, int limit);
}
