package com.example.mall.medicineservice.service;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.MedicineDTO;
import com.example.mall.medicineservice.model.Medicine;
import com.example.mall.medicineservice.repository.MedicineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MedicineService {

    private final MedicineRepository medicineRepository;

    public MedicineService(MedicineRepository medicineRepository) {
        this.medicineRepository = medicineRepository;
    }

    @Transactional(readOnly = true)
    public List<MedicineDTO> listMedicines() {
        return medicineRepository.findAllByOrderByIdAsc().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public MedicineDTO getMedicine(Long id) {
        return toDTO(findEntity(id));
    }

    @Transactional
    public MedicineDTO deductStock(Long id, int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("扣减数量必须大于 0");
        }
        Medicine medicine = findEntity(id);
        int affected = medicineRepository.deductStock(id, quantity);
        if (affected == 0) {
            throw new BusinessException("库存不足，当前库存仅 " + medicine.getStock() + " 件");
        }
        medicine.setStock(medicine.getStock() - quantity);
        return toDTO(medicine);
    }

    @Transactional
    public MedicineDTO restoreStock(Long id, int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("回补数量必须大于 0");
        }
        Medicine medicine = findEntity(id);
        medicineRepository.restoreStock(id, quantity);
        medicine.setStock(medicine.getStock() + quantity);
        return toDTO(medicine);
    }

    private Medicine findEntity(Long id) {
        return medicineRepository.findById(id)
                .orElseThrow(() -> new BusinessException("药品不存在"));
    }

    private MedicineDTO toDTO(Medicine m) {
        if (m.getPrice() == null || m.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("药品价格异常");
        }
        MedicineDTO dto = new MedicineDTO();
        dto.setId(m.getId());
        dto.setName(m.getName());
        dto.setGenericName(m.getGenericName());
        dto.setDescription(m.getDescription());
        dto.setIndication(m.getIndication());
        dto.setDosageForm(m.getDosageForm());
        dto.setPrice(m.getPrice());
        dto.setStock(m.getStock());
        dto.setImageUrl(m.getImageUrl());
        dto.setCategory(m.getCategory());
        dto.setRxFlag(m.isRxFlag());
        dto.setCreatedAt(m.getCreatedAt());
        return dto;
    }
}
