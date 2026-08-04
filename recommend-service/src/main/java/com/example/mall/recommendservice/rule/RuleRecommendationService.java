package com.example.mall.recommendservice.rule;

import com.example.mall.common.model.OrderItemDTO;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.common.model.RecommendRequest;
import com.example.mall.common.model.RecommendationItemDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则兜底推荐：基于商品品类（显式 category 字段优先，其次商品名关键词）做确定性推荐。
 * 在无 API Key / DeepSeek 不可用 / 返回异常时保证推荐功能仍然可用。
 * <ul>
 *   <li>互补模式（complement）：按已购品类 → 候选品类的互补权重评分；</li>
 *   <li>同类模式（similar）：推荐与已购商品同品类的其他商品（价格降序，升级款优先）。</li>
 * </ul>
 */
@Service
public class RuleRecommendationService {

    /** 来源标记 */
    private static final String SOURCE_RULE = "rule";

    /** 关键词 → 品类（依次匹配，命中即返回该品类）。品类名与商品种子的 category 字段保持一致。 */
    private static final List<String[]> CATEGORY_RULES = List.of(
            // 平板（先于手机品牌关键词，避免"小米"/"华为"前缀产品被误判）
            new String[]{"平板", "iPad"},
            new String[]{"平板", "MatePad"},
            new String[]{"平板", "平板"},
            // 智能音箱
            new String[]{"智能音箱", "小爱"},
            new String[]{"智能音箱", "天猫精灵"},
            new String[]{"智能音箱", "音箱"},
            // 显示器
            new String[]{"显示器", "显示器"},
            // 键盘
            new String[]{"键盘", "MX Keys"},
            new String[]{"键盘", "机械键盘"},
            new String[]{"键盘", "键盘"},
            // 鼠标
            new String[]{"鼠标", "MX Master"},
            new String[]{"鼠标", "雷蛇"},
            new String[]{"鼠标", "鼠标"},
            // 电视
            new String[]{"电视", "OLED 电视"},
            new String[]{"电视", "电视"},
            // 厨房电器
            new String[]{"厨房电器", "电饭煲"},
            new String[]{"厨房电器", "空气炸锅"},
            // 清洁家电
            new String[]{"清洁家电", "扫拖"},
            new String[]{"清洁家电", "戴森"},
            new String[]{"清洁家电", "吸尘器"},
            // 数码配件
            new String[]{"数码配件", "充电宝"},
            new String[]{"数码配件", "氮化镓"},
            new String[]{"数码配件", "充电器"},
            // 运动鞋
            new String[]{"运动鞋", "Air Max"},
            new String[]{"运动鞋", "跑鞋"},
            new String[]{"运动鞋", "耐克"},
            new String[]{"运动鞋", "鞋"},
            // 玩具
            new String[]{"玩具", "乐高"},
            // 手机
            new String[]{"手机", "iPhone"},
            new String[]{"手机", "Mate 60"},
            // 电脑
            new String[]{"电脑", "MacBook"},
            new String[]{"电脑", "拯救者"},
            new String[]{"电脑", "笔记本"},
            new String[]{"电脑", "电脑"},
            // 耳机
            new String[]{"耳机", "WH-1000"},
            new String[]{"耳机", "AirPods"},
            new String[]{"耳机", "耳机"},
            // 智能手表
            new String[]{"智能手表", "Watch"},
            new String[]{"智能手表", "手表"});

    /** 互补权重：已购品类 → {候选品类: 权重} */
    private static final Map<String, Map<String, Integer>> COMPLEMENTS = Map.ofEntries(
            Map.entry("手机", Map.of("耳机", 3, "智能手表", 2, "数码配件", 2, "平板", 1, "电脑", 1, "手机", 1)),
            Map.entry("电脑", Map.of("显示器", 3, "键盘", 3, "鼠标", 3, "耳机", 2, "手机", 1, "电脑", 1)),
            Map.entry("平板", Map.of("键盘", 3, "耳机", 2, "鼠标", 2, "数码配件", 1, "平板", 1)),
            Map.entry("耳机", Map.of("手机", 2, "智能手表", 2, "电脑", 1, "耳机", 1)),
            Map.entry("智能手表", Map.of("手机", 2, "耳机", 2, "运动鞋", 1, "智能手表", 1)),
            Map.entry("显示器", Map.of("键盘", 2, "鼠标", 2, "电脑", 2)),
            Map.entry("键盘", Map.of("鼠标", 3, "显示器", 3, "电脑", 1)),
            Map.entry("鼠标", Map.of("键盘", 3, "显示器", 2, "电脑", 1)),
            Map.entry("智能音箱", Map.of("电视", 2, "手机", 1, "智能手表", 1)),
            Map.entry("电视", Map.of("智能音箱", 2, "数码配件", 1, "电视", 1)),
            Map.entry("厨房电器", Map.of("清洁家电", 2, "数码配件", 1)),
            Map.entry("清洁家电", Map.of("数码配件", 1, "厨房电器", 1, "运动鞋", 1)),
            Map.entry("数码配件", Map.of("手机", 2, "平板", 1, "耳机", 1)),
            Map.entry("运动鞋", Map.of("智能手表", 2, "耳机", 1, "运动鞋", 1)),
            Map.entry("玩具", Map.of("手机", 1, "耳机", 1, "玩具", 1)));

    /** 互补推荐理由模板：key = "已购品类|候选品类" */
    private static final Map<String, String> REASON_TEMPLATES = Map.ofEntries(
            Map.entry("手机|耳机", "与「%s」搭配的「%s」，通勤、观影更沉浸"),
            Map.entry("手机|智能手表", "买了「%s」，再配一块「%s」，健康数据一手掌握"),
            Map.entry("手机|电脑", "「%s」+「%s」跨设备协同，办公效率翻倍"),
            Map.entry("手机|数码配件", "买了「%s」，再备「%s」，出门电量无忧"),
            Map.entry("手机|手机", "「%s」是热门机型，与「%s」一起对比挑选"),
            Map.entry("电脑|耳机", "为「%s」配一副「%s」，开会、追剧声音更清晰"),
            Map.entry("电脑|显示器", "为「%s」配一台「%s」，扩展视野，多任务更高效"),
            Map.entry("电脑|键盘", "「%s」加「%s」，输入手感大幅提升"),
            Map.entry("电脑|鼠标", "「%s」+「%s」，办公操作更顺手"),
            Map.entry("电脑|手机", "「%s」与「%s」组成高效办公组合"),
            Map.entry("电脑|电脑", "升级生产力：「%s」与「%s」"),
            Map.entry("耳机|手机", "已有「%s」，再入手「%s」让好音乐随时相伴"),
            Map.entry("耳机|智能手表", "「%s」加「%s」，运动听歌两不误"),
            Map.entry("耳机|电脑", "把「%s」接到「%s」上，沉浸感拉满"),
            Map.entry("智能手表|手机", "用「%s」记录运动，用「%s」随时查看数据"),
            Map.entry("智能手表|耳机", "运动时佩戴「%s」与「%s」，节奏感拉满"),
            Map.entry("智能手表|运动鞋", "买了「%s」，再入一双「%s」凑齐运动装备"),
            Map.entry("运动鞋|智能手表", "运动数据一手掌握：「%s」+「%s」"),
            Map.entry("运动鞋|耳机", "跑步听歌更带感：「%s」配「%s」"),
            Map.entry("清洁家电|智能手表", "家务交给「%s」，犒劳自己一块「%s」"),
            Map.entry("清洁家电|耳机", "「%s」干活时，戴上「%s」不枯燥"),
            Map.entry("玩具|手机", "拼完「%s」，用「%s」记录美好瞬间"),
            Map.entry("玩具|耳机", "「%s」拼搭时，配「%s」更有氛围"),
            Map.entry("平板|键盘", "「%s」+「%s」，移动办公效率翻倍"),
            Map.entry("平板|耳机", "「%s」配「%s」，追剧娱乐更沉浸"),
            Map.entry("键盘|鼠标", "「%s」配「%s」，键鼠一套体验更完整"),
            Map.entry("鼠标|键盘", "「%s」配「%s」，键鼠一套体验更完整"),
            Map.entry("显示器|键盘", "「%s」+「%s」，桌面生产力直接拉满"),
            Map.entry("显示器|鼠标", "「%s」配「%s」，精细操作更跟手"),
            Map.entry("电视|智能音箱", "「%s」配「%s」，家庭影院声场更震撼"),
            Map.entry("智能音箱|电视", "「%s」搭配「%s」，客厅娱乐更沉浸"),
            Map.entry("玩具|玩具", "「%s」拼完不过瘾，再来一件「%s」"));

    private static final String DEFAULT_REASON = "「%s」与您刚买的「%s」很搭配，值得一起入手";
    private static final String FILL_REASON = "热销好物「%s」，很多用户会一起入手";
    /** 同类推荐理由：候选 + 已购锚点商品 */
    private static final String SIMILAR_REASON = "「%s」与「%s」同品类，可对比挑选或升级入手";

    /**
     * 互补推荐：排除已购，互补分降序取前 topN 条；互补分为 0 的候选用「热销好物」补足剩余名额。
     * 排序确定性：分数降序 → 价格降序 → id 升序。
     */
    public List<RecommendationItemDTO> recommend(RecommendRequest request, int topN) {
        List<ProductDTO> catalog = request.getCatalog();
        List<OrderItemDTO> purchased = request.getPurchasedItems();
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductDTO> byId = indexById(catalog);
        Set<Long> purchasedIds = purchasedIds(purchased);

        List<Candidate> candidates = new ArrayList<>();
        for (ProductDTO product : catalog) {
            if (purchasedIds.contains(product.getId())) {
                continue;
            }
            candidates.add(new Candidate(product, score(product, purchased, byId)));
        }

        candidates.sort(Comparator.comparingInt((Candidate c) -> c.score)
                .reversed()
                .thenComparing(c -> c.product.getPrice(), Comparator.reverseOrder())
                .thenComparing(c -> c.product.getId()));

        List<RecommendationItemDTO> result = new ArrayList<>();
        for (Candidate c : candidates) {
            if (result.size() >= topN) {
                break;
            }
            if (c.score > 0) {
                result.add(toItem(c.product, reason(c.anchorName, c.product.getName())));
            }
        }
        for (Candidate c : candidates) {
            if (result.size() >= topN) {
                break;
            }
            result.add(toItem(c.product, String.format(FILL_REASON, c.product.getName())));
        }
        return result;
    }

    /**
     * 同类推荐：与已购商品同品类（catalog 显式 category 优先、其次名称关键词），
     * 排除已购，价格降序 → id 升序（升级款优先）；不足 topN 用互补/热销补足。
     */
    public List<RecommendationItemDTO> recommendSimilar(RecommendRequest request, int topN) {
        List<ProductDTO> catalog = request.getCatalog();
        List<OrderItemDTO> purchased = request.getPurchasedItems();
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductDTO> byId = indexById(catalog);
        Set<Long> purchasedIds = purchasedIds(purchased);

        // 已购品类集合 + 每个品类的锚点商品名（用于生成推荐理由）
        Set<String> anchors = new LinkedHashSet<>();
        Map<String, String> anchorNameByCategory = new LinkedHashMap<>();
        if (purchased != null) {
            for (OrderItemDTO item : purchased) {
                if (item.getProductId() == null) {
                    continue;
                }
                String c = purchasedCategory(item, byId);
                if (c != null && anchors.add(c)) {
                    anchorNameByCategory.put(c, item.getProductName());
                }
            }
        }

        // 同类主选：同品类、排除已购
        List<ProductDTO> same = new ArrayList<>();
        for (ProductDTO product : catalog) {
            if (purchasedIds.contains(product.getId())) {
                continue;
            }
            String cat = categoryOf(product);
            if (cat != null && anchors.contains(cat)) {
                same.add(product);
            }
        }
        same.sort(Comparator.comparing(ProductDTO::getPrice, Comparator.reverseOrder())
                .thenComparing(ProductDTO::getId));

        List<RecommendationItemDTO> result = new ArrayList<>();
        Set<Long> picked = new LinkedHashSet<>();
        for (ProductDTO product : same) {
            if (result.size() >= topN) {
                break;
            }
            if (picked.add(product.getId())) {
                String anchorName = anchorNameByCategory.get(categoryOf(product));
                result.add(toItem(product, String.format(SIMILAR_REASON,
                        product.getName(), anchorName == null ? "您已购的商品" : anchorName)));
            }
        }
        // 不足 topN 用互补/热销补足
        if (result.size() < topN) {
            for (RecommendationItemDTO item : recommend(request, topN)) {
                if (result.size() >= topN) {
                    break;
                }
                if (picked.add(item.getProductId())) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    /* ---------- 品类解析 ---------- */

    /** 商品品类：显式 category 字段优先，为空时按名称关键词推断 */
    private String categoryOf(ProductDTO product) {
        if (product == null) {
            return null;
        }
        if (product.getCategory() != null && !product.getCategory().isBlank()) {
            return product.getCategory().trim();
        }
        return category(product.getName());
    }

    /** 已购明细品类：优先从目录按 productId 反查（快照无 category），查不到再按名称关键词推断 */
    private String purchasedCategory(OrderItemDTO item, Map<Long, ProductDTO> byId) {
        ProductDTO p = item.getProductId() != null ? byId.get(item.getProductId()) : null;
        if (p != null && p.getCategory() != null && !p.getCategory().isBlank()) {
            return p.getCategory().trim();
        }
        return category(item.getProductName());
    }

    private Map<Long, ProductDTO> indexById(List<ProductDTO> catalog) {
        Map<Long, ProductDTO> byId = new LinkedHashMap<>();
        for (ProductDTO p : catalog) {
            byId.put(p.getId(), p);
        }
        return byId;
    }

    private Set<Long> purchasedIds(List<OrderItemDTO> purchased) {
        Set<Long> ids = new LinkedHashSet<>();
        if (purchased != null) {
            for (OrderItemDTO item : purchased) {
                if (item.getProductId() != null) {
                    ids.add(item.getProductId());
                }
            }
        }
        return ids;
    }

    /* ---------- 互补评分 ---------- */

    /** 计算候选商品相对全部已购商品的累计互补分，并记住最强命中的已购商品名（用于生成理由） */
    private Candidate.Score score(ProductDTO product, List<OrderItemDTO> purchased, Map<Long, ProductDTO> byId) {
        String candCat = categoryOf(product);
        int total = 0;
        String bestAnchor = null;
        int bestWeight = 0;
        if (purchased != null) {
            for (OrderItemDTO item : purchased) {
                String purCat = purchasedCategory(item, byId);
                int w = complementWeight(purCat, candCat);
                if (w > 0) {
                    total += w;
                    if (w > bestWeight) {
                        bestWeight = w;
                        bestAnchor = item.getProductName();
                    }
                }
            }
        }
        return new Candidate.Score(total, bestAnchor);
    }

    private int complementWeight(String purchasedCat, String candidateCat) {
        if (purchasedCat == null || candidateCat == null) {
            return 0;
        }
        Map<String, Integer> map = COMPLEMENTS.get(purchasedCat);
        return map == null ? 0 : map.getOrDefault(candidateCat, 0);
    }

    /** 关键词匹配品类（依次匹配，命中即返回） */
    private String category(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase();
        for (String[] rule : CATEGORY_RULES) {
            if (lower.contains(rule[1].toLowerCase())) {
                return rule[0];
            }
        }
        return null;
    }

    private String reason(String anchorName, String candidateName) {
        // 优先用「已购品类|候选品类」的定制话术
        String key = category(anchorName) + "|" + category(candidateName);
        String template = REASON_TEMPLATES.get(key);
        if (template != null) {
            return String.format(template, anchorName, candidateName);
        }
        // 无定制话术时用兜底模板
        return String.format(DEFAULT_REASON, candidateName, anchorName == null ? "您已购的商品" : anchorName);
    }

    private RecommendationItemDTO toItem(ProductDTO product, String reason) {
        RecommendationItemDTO item = new RecommendationItemDTO();
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setPrice(product.getPrice());
        item.setImageUrl(product.getImageUrl());
        item.setReason(reason);
        item.setSource(SOURCE_RULE);
        return item;
    }

    private static class Candidate {
        final ProductDTO product;
        final int score;
        final String anchorName;

        Candidate(ProductDTO product, Score s) {
            this.product = product;
            this.score = s.score;
            this.anchorName = s.anchorName;
        }

        static class Score {
            final int score;
            final String anchorName;

            Score(int score, String anchorName) {
                this.score = score;
                this.anchorName = anchorName;
            }
        }
    }
}
