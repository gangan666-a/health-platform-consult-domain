package com.example.mall.orderservice.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 下单表单：商品 + 数量 + 收货信息
 */
public class OrderForm {

    @NotNull
    private Long productId;

    @NotNull(message = "请填写购买数量")
    @Min(value = 1, message = "购买数量必须大于 0")
    @Max(value = 999, message = "单次最多购买 999 件")
    private Integer quantity;

    @NotBlank(message = "请填写收货人姓名")
    private String receiverName;

    @NotBlank(message = "请填写手机号")
    private String receiverPhone;

    @NotBlank(message = "请填写收货地址")
    private String receiverAddress;

    /**
     * 处方单号：仅处方药必填（Rx 校验在控制器针对 isRxFlag 单独做，此处只约束长度，
     * 避免非处方单号超长；必填校验走 {@code rejectValue("prescriptionNo","rxRequired",...)}）。
     */
    @Size(max = 32, message = "处方单号过长")
    private String prescriptionNo;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress = receiverAddress;
    }

    public String getPrescriptionNo() {
        return prescriptionNo;
    }

    public void setPrescriptionNo(String prescriptionNo) {
        this.prescriptionNo = prescriptionNo;
    }
}
