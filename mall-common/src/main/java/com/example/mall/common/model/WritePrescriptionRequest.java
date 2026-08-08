package com.example.mall.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 医生开方请求（order-service → consult-service）。
 * <p>
 * 校验链（fail-closed）：会话存在 → status=ONGOING → 由接管医生操作 → 用法用量/数量非空合法。
 * 开方与会话置 PRESCRIPTION_ISSUED 在同一事务内完成。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WritePrescriptionRequest {

    private Long sessionId;
    private List<PrescriptionItem> items;
    private String note;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public List<PrescriptionItem> getItems() {
        return items;
    }

    public void setItems(List<PrescriptionItem> items) {
        this.items = items;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
