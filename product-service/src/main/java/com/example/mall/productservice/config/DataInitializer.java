package com.example.mall.productservice.config;

import com.example.mall.productservice.model.Product;
import com.example.mall.productservice.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 种子数据：按「商品名 upsert」——名字不存在则插入（含品类），
 * 已存在且品类为空则只回填品类（不动库存/价格/描述）。
 * 幂等：第二次启动全部命中且品类非空时无操作。
 */
@Configuration
public class DataInitializer {

    /** 单个种子商品 */
    private record SeedProduct(String name, String category, String description, BigDecimal price, int stock, String imageUrl) {
    }

    private static final List<SeedProduct> SEEDS = List.of(
            // 手机
            new SeedProduct("iPhone 15 Pro", "手机", "6.1 英寸超视网膜 XDR 屏，A17 Pro 芯片，钛金属边框", new BigDecimal("7999.00"), 50, "📱"),
            new SeedProduct("小米 14", "手机", "骁龙 8 Gen 3，徕卡光学镜头，16GB+512GB", new BigDecimal("4299.00"), 80, "📸"),
            new SeedProduct("华为 Mate 60 Pro", "手机", "麒麟 9000S，卫星通话，昆仑玻璃，超可靠玄武架构", new BigDecimal("6999.00"), 70, "📱"),
            // 电脑
            new SeedProduct("MacBook Pro 14", "电脑", "14 英寸 Liquid 视网膜 XDR 屏，M3 Pro 芯片，续航 18 小时", new BigDecimal("14999.00"), 20, "💻"),
            new SeedProduct("联想拯救者 Y9000P", "电脑", "14 代酷睿 i9 + RTX 4060，2.5K 165Hz 电竞屏", new BigDecimal("8999.00"), 30, "💻"),
            // 平板
            new SeedProduct("iPad Air 11", "平板", "M2 芯片，11 英寸 Liquid 视网膜屏，支持 Apple Pencil Pro", new BigDecimal("4799.00"), 40, "📲"),
            new SeedProduct("华为 MatePad Pro 13.2", "平板", "13.2 英寸柔性 OLED 屏，星闪连接，生产力自由窗口", new BigDecimal("5199.00"), 35, "📲"),
            // 耳机
            new SeedProduct("索尼 WH-1000XM5", "耳机", "行业标杆降噪，30 小时续航，头戴式无线耳机", new BigDecimal("2499.00"), 40, "🎧"),
            new SeedProduct("苹果 AirPods Pro 2", "耳机", "主动降噪 + 通透模式，自适应音频，USB-C 充电盒", new BigDecimal("1899.00"), 100, "🎧"),
            // 智能手表
            new SeedProduct("Apple Watch Series 9", "智能手表", "全天候视网膜屏，S9 双核芯片，血氧与心率监测", new BigDecimal("2999.00"), 60, "⌚"),
            new SeedProduct("华为 Watch GT 4", "智能手表", "46mm 曜石黑，健康管理再进化，两周超长续航", new BigDecimal("1488.00"), 90, "⌚"),
            // 显示器
            new SeedProduct("戴尔 27 英寸 4K 显示器", "显示器", "U2723QX，27 英寸 4K IPS，98% DCI-P3，USB-C 90W 反向充电", new BigDecimal("3999.00"), 25, "🖥️"),
            new SeedProduct("小米 27 英寸 2K 显示器", "显示器", "2K 高清，165Hz 刷新率，HDR400，窄边框", new BigDecimal("1299.00"), 60, "🖥️"),
            // 键盘
            new SeedProduct("罗技 MX Keys", "键盘", "智能背光，PerfectStroke 键帽，多设备无线切换", new BigDecimal("749.00"), 80, "⌨️"),
            new SeedProduct("樱桃 MX 3.0S 机械键盘", "键盘", "原厂 CHERRY MX 轴，无钢设计，全键无冲", new BigDecimal("1099.00"), 50, "⌨️"),
            // 鼠标
            new SeedProduct("罗技 MX Master 3S", "鼠标", "8000DPI 静音微动，电磁滚轮，跨设备一键切换", new BigDecimal("699.00"), 70, "🖱️"),
            new SeedProduct("雷蛇 炼狱蝰蛇 V2", "鼠标", "20000DPI 光学传感器，轻量化设计，游戏电竞鼠标", new BigDecimal("399.00"), 90, "🖱️"),
            // 智能音箱
            new SeedProduct("小米 小爱音箱 Pro", "智能音箱", "小米澎湃 OS 接入，红外遥控家电，360° 环绕音效", new BigDecimal("349.00"), 120, "🔊"),
            new SeedProduct("天猫精灵 X5", "智能音箱", "火山引擎大模型，声纹识别，家庭智能中枢", new BigDecimal("399.00"), 100, "🔊"),
            // 电视
            new SeedProduct("索尼 65 英寸 OLED 电视", "电视", "XR 认知芯片，OLED 自发光，120Hz 高刷，沉浸式画谛", new BigDecimal("11999.00"), 10, "📺"),
            new SeedProduct("小米 55 英寸 4K 电视", "电视", "4K 金属全面屏，HDR10+，远场语音，开机即智能电视系统", new BigDecimal("3999.00"), 20, "📺"),
            // 厨房电器
            new SeedProduct("美的 电饭煲 4L", "厨房电器", "IH 电磁加热，精煮柴火饭，触控预约，4 升大容量", new BigDecimal("399.00"), 150, "🍚"),
            new SeedProduct("飞利浦 空气炸锅", "厨房电器", "6.3L 大容量，数字触控，无油低脂更健康", new BigDecimal("449.00"), 120, "🍟"),
            // 清洁家电
            new SeedProduct("戴森 V12 无绳吸尘器", "清洁家电", "激光探测灰尘，180AW 强劲吸力，续航 60 分钟", new BigDecimal("3699.00"), 15, "🧹"),
            new SeedProduct("石头 扫拖机器人 P10", "清洁家电", "自动集尘 + 自清洁拖布，全屋智能清扫规划", new BigDecimal("2999.00"), 25, "🤖"),
            // 数码配件
            new SeedProduct("小米 10000mAh 充电宝", "数码配件", "轻薄便携，22.5W 快充，双向快充，日常出行必备", new BigDecimal("99.00"), 200, "🔋"),
            new SeedProduct("Anker 氮化镓 65W 充电器", "数码配件", "氮化镓黑科技，体积更小，三口同时快充", new BigDecimal("129.00"), 180, "🔌"),
            // 运动鞋
            new SeedProduct("耐克 Air Max 270", "运动鞋", "经典气垫跑鞋，网眼鞋面透气舒适，多色可选", new BigDecimal("899.00"), 100, "👟"),
            new SeedProduct("阿迪达斯 Ultraboost 跑鞋", "运动鞋", "Boost 中底回弹缓震，Primeknit 编织鞋面，长跑利器", new BigDecimal("1099.00"), 80, "👟"),
            // 玩具
            new SeedProduct("乐高 千年隼号", "玩具", "7541 颗粒，附展示支架与专属铭牌", new BigDecimal("1699.00"), 10, "🧩"),
            new SeedProduct("乐高 保时捷 911 GT3", "玩具", "2704 颗粒，1:8 比例，精致机械细节，收藏级拼搭", new BigDecimal("1299.00"), 15, "🏎️"));

    @Bean
    CommandLineRunner loadData(ProductRepository productRepository) {
        return args -> {
            // 幂等 upsert：老库已有商品时不清空，只补新品 + 回填空品类
            Map<String, Product> byName = productRepository.findAll().stream()
                    .collect(Collectors.toMap(Product::getName, p -> p, (a, b) -> a));
            List<Product> toSave = new ArrayList<>();
            for (SeedProduct seed : SEEDS) {
                Product existing = byName.get(seed.name());
                if (existing == null) {
                    toSave.add(new Product(seed.name(), seed.description(), seed.price(),
                            seed.stock(), seed.imageUrl(), seed.category()));
                } else if (existing.getCategory() == null || existing.getCategory().isBlank()) {
                    existing.setCategory(seed.category());
                    toSave.add(existing);
                }
            }
            if (!toSave.isEmpty()) {
                productRepository.saveAll(toSave);
            }
        };
    }
}
