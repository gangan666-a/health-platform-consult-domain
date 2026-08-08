package com.example.mall.consultservice.model;

import com.example.mall.common.model.PrescriptionItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * 处方明细 List<PrescriptionItem> ↔ JSON 字符串互转。
 * 静态 ObjectMapper（与 ClientSupport 同风格），JPA AttributeConverter 非 Spring Bean 无法注入。
 */
@Converter
public class ItemsJsonConverter implements AttributeConverter<List<PrescriptionItem>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<PrescriptionItem>> ITEMS_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<PrescriptionItem> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception ex) {
            throw new IllegalStateException("处方明细序列化失败", ex);
        }
    }

    @Override
    public List<PrescriptionItem> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, ITEMS_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("处方明细反序列化失败", ex);
        }
    }
}
