package com.example.mall.productservice;

import com.example.mall.common.model.ProductDTO;
import com.example.mall.productservice.model.Product;
import com.example.mall.productservice.repository.ProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProductService 功能/集成测试（@SpringBootTest + MockMvc + H2 内存库）。
 * - src/test/resources/application.properties 用 H2 覆盖主配置，不连外部 MySQL；
 * - DataInitializer 种子数据保留（真实上下文验证列表有数据，39 个商品：31 个 M1 + 8 个药品）；
 * - 每个测试方法 @Transactional，结束时回滚，避免用例间状态泄漏。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("ProductService 功能测试（H2）")
class ProductFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    /** 保存一个独立测试商品，返回 id；随测试事务回滚 */
    private Long saveProduct(String name, int stock) {
        Product p = productRepository.save(
                new Product(name, "测试描述", new BigDecimal("100.00"), stock, "img", "测试品类"));
        return p.getId();
    }

    @Test
    @DisplayName("真实上下文：列表有种子数据（39 个商品：31 M1 + 8 药品），字段完整，非药品 rxFlag=false")
    void list_hasSeedData() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(39))
                .andExpect(jsonPath("$[0].name").value("iPhone 15 Pro"))
                .andExpect(jsonPath("$[0].category").value("手机"))
                .andExpect(jsonPath("$[0].price").value(7999.00))
                .andExpect(jsonPath("$[0].rxFlag").value(false));
    }

    @Test
    @DisplayName("真实上下文：药品品类种子 OTC/Rx 标记正确（FR-D1 / AC-D1-1，DTO 序列化 rxFlag）")
    void drugSeeds_rxFlag() throws Exception {
        // 仓库按名取 id，再走 REST 详情断言 rxFlag 序列化（避免 jsonPath 过滤器表达式解析中文的兼容问题）
        Long amoxId = productRepository.findAll().stream()
                .filter(p -> "阿莫西林胶囊".equals(p.getName()))
                .findFirst().orElseThrow().getId();
        Long ganmaoId = productRepository.findAll().stream()
                .filter(p -> "感冒灵颗粒".equals(p.getName()))
                .findFirst().orElseThrow().getId();

        // Rx 处方药：详情 DTO 序列化 rxFlag=true，品类=药品
        mockMvc.perform(get("/api/products/{id}", amoxId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rxFlag").value(true))
                .andExpect(jsonPath("$.category").value("药品"));
        // OTC 非处方药：rxFlag=false
        mockMvc.perform(get("/api/products/{id}", ganmaoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rxFlag").value(false));

        // 种子回填完整性：8 个药品中 4 个 Rx = true、4 个 OTC = false
        Map<String, Boolean> byName = productRepository.findAll().stream()
                .collect(Collectors.toMap(Product::getName, Product::isRxFlag));
        assertEquals(Boolean.TRUE, byName.get("阿莫西林胶囊"));
        assertEquals(Boolean.TRUE, byName.get("盐酸左氧氟沙星片"));
        assertEquals(Boolean.TRUE, byName.get("硝苯地平缓释片"));
        assertEquals(Boolean.TRUE, byName.get("布洛芬缓释胶囊"));
        assertEquals(Boolean.FALSE, byName.get("感冒灵颗粒"));
        assertEquals(Boolean.FALSE, byName.get("维生素C片"));
        assertEquals(Boolean.FALSE, byName.get("蒙脱石散"));
        assertEquals(Boolean.FALSE, byName.get("板蓝根颗粒"));
    }

    /**
     * 真实上下文：列表 DTO 全量序列化 rxFlag。
     * Rx/OTC 各抽查一个种子商品断言 rxFlag 正确；药品品类 8 项中 4 Rx=4 OTC，
     * 若任一药品项 rxFlag 缺失/错位（如被误判为处方药）此断言即失败（PRD FR-D1 / AC-D1-5 fail-closed）。
     */
    @Test
    @DisplayName("真实上下文：列表 DTO 携带 rxFlag（Rx/OTC 抽查 + 药品品类 4:4 无缺失）")
    void list_drugItemsCarryRxFlag() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn();
        List<ProductDTO> list = new ObjectMapper().findAndRegisterModules().readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<List<ProductDTO>>() {
                });
        assertFalse(list.isEmpty());

        // 药品品类 8 项全部携带 rxFlag，且 Rx:OTC = 4:4（无缺失/无错位）
        List<ProductDTO> drugs = list.stream()
                .filter(p -> "药品".equals(p.getCategory()))
                .toList();
        assertEquals(8, drugs.size());
        assertEquals(4, drugs.stream().filter(ProductDTO::isRxFlag).count());

        // Rx 抽查：阿莫西林胶囊 → true
        assertTrue(list.stream().filter(p -> "阿莫西林胶囊".equals(p.getName()))
                .findFirst().orElseThrow().isRxFlag());
        // OTC 抽查：感冒灵颗粒 → false
        assertFalse(list.stream().filter(p -> "感冒灵颗粒".equals(p.getName()))
                .findFirst().orElseThrow().isRxFlag());
    }

    @Test
    @DisplayName("真实上下文：详情查询成功")
    void detail_ok() throws Exception {
        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("iPhone 15 Pro"));
    }

    @Test
    @DisplayName("真实上下文：详情不存在 → 400 {\"message\":\"商品不存在\"}")
    void detail_notFound() throws Exception {
        mockMvc.perform(get("/api/products/999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("商品不存在"));
    }

    @Test
    @DisplayName("真实上下文：扣减→查库存→再扣到不足→库存不变（EX-1 / AC-FR3-6）")
    void deduct_thenInsufficient() throws Exception {
        Long id = saveProduct("功能扣减商品", 3);
        // 扣 2 → 库存 1
        mockMvc.perform(post("/api/products/{id}/stock/deduct", id).param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(1));
        // 详情确认库存已扣减为 1
        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(1));
        // 再扣 2 → 库存不足，返回 400，message 精确
        mockMvc.perform(post("/api/products/{id}/stock/deduct", id).param("quantity", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("库存不足，当前库存仅 1 件"));
        // 库存未被改变（仍为 1）
        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(1));
    }

    @Test
    @DisplayName("真实上下文：回补恢复库存；回补超过原始库存上限当前实现允许（记录）")
    void restore_backAndOver() throws Exception {
        Long id = saveProduct("功能回补商品", 3);
        mockMvc.perform(post("/api/products/{id}/stock/deduct", id).param("quantity", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(1));
        // 回补 2 → 库存恢复为 3（AC-FR3-2）
        mockMvc.perform(post("/api/products/{id}/stock/restore", id).param("quantity", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(3));
        // 回补 100 → 103（超过原始上限 3，当前实现无条件追加，允许）
        mockMvc.perform(post("/api/products/{id}/stock/restore", id).param("quantity", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(103));
    }

    @Test
    @DisplayName("真实上下文：非法参数 400（0 / 负数 / 非数字 / 商品不存在）")
    void illegalParams() throws Exception {
        Long id = saveProduct("功能参数商品", 3);
        // quantity=0 → 扣减数量必须大于 0
        mockMvc.perform(post("/api/products/{id}/stock/deduct", id).param("quantity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("扣减数量必须大于 0"));
        // quantity=-1 → 扣减数量必须大于 0
        mockMvc.perform(post("/api/products/{id}/stock/deduct", id).param("quantity", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("扣减数量必须大于 0"));
        // quantity=abc → 参数非法
        mockMvc.perform(post("/api/products/{id}/stock/deduct", id).param("quantity", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数非法"));
        // 商品不存在
        mockMvc.perform(post("/api/products/999999/stock/deduct").param("quantity", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("商品不存在"));
    }
}
