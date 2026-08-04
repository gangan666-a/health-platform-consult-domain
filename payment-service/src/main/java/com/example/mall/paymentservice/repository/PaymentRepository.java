package com.example.mall.paymentservice.repository;

import com.example.mall.paymentservice.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);
}
