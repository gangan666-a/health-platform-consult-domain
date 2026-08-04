package com.example.mall.productservice.repository;

import com.example.mall.productservice.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByOrderByIdAsc();

    /**
     * 原子扣减库存：单条 UPDATE 语句，WHERE 带库存校验，
     * 返回影响行数（0 = 库存不足，天然防并发超卖）。
     */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - :qty WHERE p.id = :id AND p.stock >= :qty")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 回补库存 */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock + :qty WHERE p.id = :id")
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);
}
