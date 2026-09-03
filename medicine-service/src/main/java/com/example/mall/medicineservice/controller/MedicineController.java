package com.example.mall.medicineservice.controller;

import com.example.mall.common.model.MedicineDTO;
import com.example.mall.medicineservice.service.MedicineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/medicines")
public class MedicineController {

    private final MedicineService medicineService;

    public MedicineController(MedicineService medicineService) {
        this.medicineService = medicineService;
    }

    @GetMapping
    public List<MedicineDTO> list() {
        return medicineService.listMedicines();
    }

    @GetMapping("/{id}")
    public MedicineDTO detail(@PathVariable Long id) {
        return medicineService.getMedicine(id);
    }

    @PostMapping("/{id}/stock/deduct")
    public MedicineDTO deduct(@PathVariable Long id, @RequestParam(name = "quantity") int quantity) {
        return medicineService.deductStock(id, quantity);
    }

    @PostMapping("/{id}/stock/restore")
    public MedicineDTO restore(@PathVariable Long id,
                               @RequestParam(name = "quantity", defaultValue = "1") int quantity) {
        return medicineService.restoreStock(id, quantity);
    }
}
