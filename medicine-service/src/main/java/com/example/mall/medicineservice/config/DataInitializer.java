package com.example.mall.medicineservice.config;

import com.example.mall.medicineservice.model.Medicine;
import com.example.mall.medicineservice.repository.MedicineRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class DataInitializer {

    private record SeedMedicine(String name, String genericName, String category, String indication,
                                String dosageForm, String description, BigDecimal price, int stock,
                                String imageUrl, boolean rxFlag) {
    }

    private static final List<SeedMedicine> SEEDS = List.of(
            new SeedMedicine("阿莫西林胶囊", "阿莫西林", "抗感染", "细菌感染", "胶囊",
                    "青霉素类抗生素，需凭处方购买", new BigDecimal("12.50"), 200, "💊", true),
            new SeedMedicine("盐酸左氧氟沙星片", "左氧氟沙星", "抗感染", "细菌感染", "片剂",
                    "喹诺酮类抗菌药，需凭处方购买", new BigDecimal("18.90"), 150, "💊", true),
            new SeedMedicine("硝苯地平缓释片", "硝苯地平", "心脑血管", "高血压", "缓释片",
                    "钙拮抗剂降压药，需凭处方购买", new BigDecimal("22.00"), 100, "💊", true),
            new SeedMedicine("布洛芬缓释胶囊", "布洛芬", "解热镇痛", "疼痛发热", "缓释胶囊",
                    "解热镇痛，Rx 规格需凭处方", new BigDecimal("15.80"), 180, "💊", true),
            new SeedMedicine("感冒灵颗粒", "复方感冒灵", "感冒", "感冒症状", "颗粒",
                    "OTC，开水冲服", new BigDecimal("9.90"), 300, "💊", false),
            new SeedMedicine("维生素C片", "维生素C", "营养补充", "维C缺乏", "片剂",
                    "OTC 营养补充", new BigDecimal("8.50"), 400, "💊", false),
            new SeedMedicine("蒙脱石散", "蒙脱石", "消化", "腹泻", "散剂",
                    "OTC 止泻", new BigDecimal("25.00"), 120, "💊", false),
            new SeedMedicine("板蓝根颗粒", "板蓝根", "感冒", "清热解毒", "颗粒",
                    "OTC 清热解毒", new BigDecimal("13.50"), 260, "💊", false),
            new SeedMedicine("奥美拉唑肠溶胶囊", "奥美拉唑", "消化", "胃酸过多", "肠溶胶囊",
                    "OTC 抑酸护胃", new BigDecimal("19.90"), 200, "💊", false),
            new SeedMedicine("氯雷他定片", "氯雷他定", "过敏", "过敏性鼻炎", "片剂",
                    "OTC 抗过敏", new BigDecimal("16.80"), 220, "💊", false));

    @Bean
    CommandLineRunner loadData(MedicineRepository repository) {
        return args -> {
            Map<String, Medicine> byName = repository.findAll().stream()
                    .collect(Collectors.toMap(Medicine::getName, m -> m, (a, b) -> a));
            List<Medicine> toSave = new ArrayList<>();
            for (SeedMedicine seed : SEEDS) {
                if (!byName.containsKey(seed.name())) {
                    toSave.add(new Medicine(seed.name(), seed.genericName(), seed.description(),
                            seed.indication(), seed.dosageForm(), seed.price(), seed.stock(),
                            seed.imageUrl(), seed.category(), seed.rxFlag()));
                }
            }
            if (!toSave.isEmpty()) {
                repository.saveAll(toSave);
            }
        };
    }
}
