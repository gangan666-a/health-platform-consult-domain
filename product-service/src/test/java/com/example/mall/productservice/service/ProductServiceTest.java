package com.example.mall.productservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ProductDTO;
import com.example.mall.productservice.model.Product;
import com.example.mall.productservice.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductService 单元测试（JUnit5 + Mockito，纯 mock 仓库，无 Spring 上下文）。
 * 目标：验证列表/详情/扣减/回补/价格校验与并发扣减到 0 后再扣拒绝等边界语义。
 */
@DisplayName("ProductService 单元测试")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        productService = new ProductService(productRepository);
    }

    /** 构造一个价格合法、库存可指定的商品 */
    private Product product(long id, BigDecimal price, int stock) {
        Product p = new Product("测试商品" + id, "测试描述", price, stock, "img", "手机");
        p.setId(id);
        p.setCreatedAt(LocalDateTime.of(2026, 8, 6, 10, 0));
        return p;
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("正常：查询列表返回按 id 升序的 DTO（AC-FR1-1）")
    void listProducts_normal() {
        when(productRepository.findAllByOrderByIdAsc())
                .thenReturn(List.of(product(1L, new BigDecimal("100.00"), 5),
                        product(2L, new BigDecimal("200.00"), 8)));
        List<ProductDTO> list = productService.listProducts();
        assertEquals(2, list.size());
        assertEquals(1L, list.get(0).getId());
        assertEquals("测试商品1", list.get(0).getName());
        assertEquals(new BigDecimal("200.00"), list.get(1).getPrice());
        assertEquals(8, list.get(1).getStock());
    }

    @Test
    @DisplayName("正常：列表 DTO 逐项携带 rxFlag（Rx=true / OTC=false，PRD FR-D1）")
    void listProducts_dtoCarriesRxFlag() {
        Product rx = product(1L, new BigDecimal("100.00"), 5);
        rx.setRxFlag(true);
        Product otc = product(2L, new BigDecimal("200.00"), 8);
        when(productRepository.findAllByOrderByIdAsc()).thenReturn(List.of(rx, otc));

        List<ProductDTO> list = productService.listProducts();
        assertTrue(list.get(0).isRxFlag(), "Rx 处方药 rxFlag 应为 true");
        assertFalse(list.get(1).isRxFlag(), "OTC 非处方药 rxFlag 应为 false");
    }

    @Test
    @DisplayName("异常：列表含价格≤0 脏数据 → 抛「商品价格异常」（EX-6 补强）")
    void listProducts_priceInvalid() {
        when(productRepository.findAllByOrderByIdAsc())
                .thenReturn(List.of(product(1L, BigDecimal.ZERO, 5)));
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.listProducts());
        assertEquals("商品价格异常", ex.getMessage());
    }

    // ==================== 详情 ====================

    @Test
    @DisplayName("正常：查询详情返回完整字段（AC-FR1-2）")
    void getProduct_normal() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, new BigDecimal("100.00"), 5)));
        ProductDTO dto = productService.getProduct(1L);
        assertNotNull(dto);
        assertEquals(1L, dto.getId());
        assertEquals("手机", dto.getCategory());
        assertEquals(5, dto.getStock());
    }

    @Test
    @DisplayName("正常：详情 DTO 携带 rxFlag（Rx=true）")
    void getProduct_dtoCarriesRxFlag() {
        Product rx = product(1L, new BigDecimal("100.00"), 5);
        rx.setRxFlag(true);
        when(productRepository.findById(1L)).thenReturn(Optional.of(rx));
        assertTrue(productService.getProduct(1L).isRxFlag());
    }

    @Test
    @DisplayName("fail-closed：老商品无 Rx 标记（默认 false）→ 一律按 OTC，不被误判为处方药（AC-D1-5）")
    void legacyProductWithoutRxFlag_defaultsToOtc() {
        // Product.rxFlag 字段默认 false，未显式 set → DTO 映射为 false（OTC）
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product(1L, new BigDecimal("100.00"), 5)));
        assertFalse(productService.getProduct(1L).isRxFlag());
    }

    @Test
    @DisplayName("异常：详情商品不存在 → 抛「商品不存在」（EX-5 / AC-FR1-3）")
    void getProduct_notFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.getProduct(99L));
        assertEquals("商品不存在", ex.getMessage());
    }

    @Test
    @DisplayName("异常：详情商品价格为 null → 抛「商品价格异常」（EX-6）")
    void getProduct_priceNull() {
        Product p = product(1L, new BigDecimal("100.00"), 5);
        p.setPrice(null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(p));
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.getProduct(1L));
        assertEquals("商品价格异常", ex.getMessage());
    }

    @Test
    @DisplayName("异常：详情商品价格≤0 → 抛「商品价格异常」（EX-6）")
    void getProduct_priceNonPositive() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, new BigDecimal("-1.00"), 5)));
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.getProduct(1L));
        assertEquals("商品价格异常", ex.getMessage());
    }

    // ==================== 扣减 ====================

    @Test
    @DisplayName("正常：扣减成功返回扣减后库存（AC-FR3-1）")
    void deductStock_normal() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, new BigDecimal("100.00"), 10)));
        when(productRepository.deductStock(anyLong(), anyInt())).thenReturn(1);
        ProductDTO dto = productService.deductStock(1L, 2);
        assertEquals(8, dto.getStock());
        verify(productRepository).deductStock(1L, 2);
    }

    @Test
    @DisplayName("异常：库存不足（原子扣减影响行数 0）→ 抛「库存不足，当前库存仅 2 件」（EX-1 / AC-FR3-6）")
    void deductStock_insufficient() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, new BigDecimal("100.00"), 2)));
        when(productRepository.deductStock(anyLong(), anyInt())).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.deductStock(1L, 5));
        assertEquals("库存不足，当前库存仅 2 件", ex.getMessage());
    }

    @Test
    @DisplayName("边界：扣减数量大于当前库存 → 拒绝（影响行数 0），库存不变")
    void deductStock_quantityExceedsStock() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, new BigDecimal("100.00"), 2)));
        when(productRepository.deductStock(anyLong(), anyInt())).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.deductStock(1L, 3));
        assertEquals("库存不足，当前库存仅 2 件", ex.getMessage());
    }

    @Test
    @DisplayName("异常：quantity=0 → 抛「扣减数量必须大于 0」，不触达仓库（EX-3 / AC-FR3-4）")
    void deductStock_zeroQuantity() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.deductStock(1L, 0));
        assertEquals("扣减数量必须大于 0", ex.getMessage());
        verify(productRepository, never()).deductStock(anyLong(), anyInt());
        verify(productRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("异常：quantity=负数 → 抛「扣减数量必须大于 0」（EX-3 / AC-FR3-4）")
    void deductStock_negativeQuantity() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.deductStock(1L, -1));
        assertEquals("扣减数量必须大于 0", ex.getMessage());
    }

    @Test
    @DisplayName("异常：扣减商品不存在 → 抛「商品不存在」（EX-5 / AC-FR3-5）")
    void deductStock_notFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.deductStock(99L, 1));
        assertEquals("商品不存在", ex.getMessage());
    }

    @Test
    @DisplayName("并发语义：多次扣减到 0 后再扣 → 拒绝（mock 模拟原子查询，确定性，不依赖真实并发）（EX-2 / AC-FR3-3）")
    void deductStock_concurrentToZeroThenReject() {
        // 用一个 AtomicInteger 模拟 DB 原子扣减的「读当前库存并判定 stock>=qty」语义，
        // 每次 findById 返回当前库存快照，deductStock 命中则扣减并返回 1，否则返回 0。
        AtomicInteger stock = new AtomicInteger(3);
        when(productRepository.findById(1L))
                .thenAnswer(inv -> Optional.of(product(1L, new BigDecimal("100.00"), stock.get())));
        when(productRepository.deductStock(anyLong(), anyInt())).thenAnswer(inv -> {
            int qty = inv.getArgument(1);
            if (stock.get() >= qty) {
                stock.addAndGet(-qty);
                return 1;
            }
            return 0;
        });
        // 三次各扣 1，库存 3 → 2 → 1 → 0
        assertEquals(2, productService.deductStock(1L, 1).getStock());
        assertEquals(1, productService.deductStock(1L, 1).getStock());
        assertEquals(0, productService.deductStock(1L, 1).getStock());
        // 库存已为 0，再扣 1 被拒绝，且库存保持 0
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.deductStock(1L, 1));
        assertEquals("库存不足，当前库存仅 0 件", ex.getMessage());
        assertEquals(0, stock.get());
    }

    @Test
    @DisplayName("说明：quantity 非整数（如 1.5）由 Controller 参数绑定层拒绝（service 入参为 int），详见 ProductControllerTest")
    void deductStock_nonIntegerIsControllerConcern() {
        // service.deductStock(Long, int) 无法接收非整数；1.5 在 Web 层绑定时抛
        // MethodArgumentTypeMismatchException → 400「参数非法」。此处不做断言，仅为可追溯性说明。
    }

    // ==================== 回补 ====================

    @Test
    @DisplayName("正常：回补成功返回回补后库存（AC-FR3-2）")
    void restoreStock_normal() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, new BigDecimal("100.00"), 8)));
        when(productRepository.restoreStock(anyLong(), anyInt())).thenReturn(1);
        ProductDTO dto = productService.restoreStock(1L, 2);
        assertEquals(10, dto.getStock());
        verify(productRepository).restoreStock(1L, 2);
    }

    @Test
    @DisplayName("边界：回补后超过原始库存上限 → 当前实现【允许】（无条件追加），记录为待确认项")
    void restoreStock_overOriginalStockAllowed() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, new BigDecimal("100.00"), 10)));
        when(productRepository.restoreStock(anyLong(), anyInt())).thenReturn(1);
        ProductDTO dto = productService.restoreStock(1L, 100);
        assertEquals(110, dto.getStock());
    }

    @Test
    @DisplayName("异常：quantity=0 / 负数 → 抛「回补数量必须大于 0」")
    void restoreStock_invalidQuantity() {
        BusinessException ex0 = assertThrows(BusinessException.class, () -> productService.restoreStock(1L, 0));
        assertEquals("回补数量必须大于 0", ex0.getMessage());
        BusinessException exNeg = assertThrows(BusinessException.class, () -> productService.restoreStock(1L, -2));
        assertEquals("回补数量必须大于 0", exNeg.getMessage());
    }

    @Test
    @DisplayName("异常：回补商品不存在 → 抛「商品不存在」（EX-5 / AC-FR3-5）")
    void restoreStock_notFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> productService.restoreStock(99L, 1));
        assertEquals("商品不存在", ex.getMessage());
    }
}
