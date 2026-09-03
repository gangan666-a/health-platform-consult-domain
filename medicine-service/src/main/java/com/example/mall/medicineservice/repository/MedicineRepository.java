package com.example.mall.medicineservice.repository;

import com.example.mall.medicineservice.model.Medicine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    List<Medicine> findAllByOrderByIdAsc();

    @Modifying
    @Query("UPDATE Medicine m SET m.stock = m.stock - :qty WHERE m.id = :id AND m.stock >= :qty")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    @Modifying
    @Query("UPDATE Medicine m SET m.stock = m.stock + :qty WHERE m.id = :id")
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);
}
