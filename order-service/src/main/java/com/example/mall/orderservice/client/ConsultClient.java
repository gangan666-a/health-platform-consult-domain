package com.example.mall.orderservice.client;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ChatMessageDTO;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsumePrescriptionItem;
import com.example.mall.common.model.ConsumePrescriptionRequest;
import com.example.mall.common.model.CreateConsultSessionRequest;
import com.example.mall.common.model.PrescriptionDTO;
import com.example.mall.common.model.PrescriptionItem;
import com.example.mall.common.model.RestorePrescriptionRequest;
import com.example.mall.common.model.WritePrescriptionRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * 问诊/处方服务调用方（order-service → consult-service，BFF 集成 F5）。
 * <p>
 * 统一超时（connect 3s / read 5s，见 {@link RestClientConfig}）；错误映射与商品/支付一致：
 * 对方返回的 {"message":"..."} 转为业务异常，网络/超时等非 HTTP 异常转
 * "问诊服务不可用，请稍后重试"。处方药购买走 fail-closed：问诊服务不可用 → 直接拒绝 Rx 订单，
 * 绝不放行无处方购药（PRD FR-D1 / 设计 §3.3）。
 */
@Component
public class ConsultClient {

    private final RestClient restClient;

    public ConsultClient(@Value("${mall.consult-service.url}") String baseUrl, RestClient.Builder builder) {
        // builder 为 RestClientConfig 注入的带超时配置的实例（connect 3s / read 5s）
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** 创建/复用问诊会话：返回用户 WS token（同药品未结束会话复用） */
    public ConsultSessionDTO createSession(Long productId, String productName) {
        CreateConsultSessionRequest req = new CreateConsultSessionRequest();
        req.setProductId(productId);
        req.setProductName(productName);
        return post("/api/consult/sessions", req, ConsultSessionDTO.class);
    }

    /** 医生接单：WAITING→ONGOING，返回医生 WS token */
    public ConsultSessionDTO acceptSession(Long sessionId, String doctorId, String doctorName) {
        return post("/api/consult/sessions/{id}/accept", Map.of("doctorId", doctorId, "doctorName", doctorName),
                ConsultSessionDTO.class, sessionId);
    }

    /** 主动结束会话（幂等），reason 缺省 USER_ENDED；医生侧传 DOCTOR_ENDED */
    public ConsultSessionDTO endSession(Long sessionId, String reason) {
        Map<String, String> body = reason == null ? null : Map.of("reason", reason);
        return post("/api/consult/sessions/{id}/end", body, ConsultSessionDTO.class, sessionId);
    }

    /** 会话详情（注意：token 恒为 null，需经 create/refreshUserToken 获取） */
    public ConsultSessionDTO getSession(Long sessionId) {
        return get("/api/consult/sessions/{id}", ConsultSessionDTO.class, sessionId);
    }

    /** 重新签发用户 WS token（断线重连取 token，R-12） */
    public ConsultSessionDTO refreshUserToken(Long sessionId) {
        return post("/api/consult/sessions/{id}/ws-token", null, ConsultSessionDTO.class, sessionId);
    }

    /** 消息历史（afterSeq 增量补拉，断线重连后补消息用） */
    public List<ChatMessageDTO> getMessages(Long sessionId, Integer afterSeq) {
        return getList("/api/consult/sessions/{id}/messages", new ParameterizedTypeReference<>() {
        }, sessionId, afterSeq);
    }

    /** 医生开方：ONGOING→PRESCRIPTION_ISSUED（同事务），返回处方 DTO */
    public PrescriptionDTO prescribe(Long sessionId, List<PrescriptionItem> items, String note) {
        WritePrescriptionRequest req = new WritePrescriptionRequest();
        req.setSessionId(sessionId);
        req.setItems(items);
        req.setNote(note);
        return post("/api/consult/prescriptions", req, PrescriptionDTO.class);
    }

    /**
     * 一次性消费处方（Rx 下单时调用，P1-02 必须携带 orderNo）。
     * 消费成功 → 会话自动结束（COMPLETED）；CAS 保证一次性，重复消费会失败。
     */
    public PrescriptionDTO consumePrescription(String prescriptionNo, String orderNo,
                                               List<ConsumePrescriptionItem> items) {
        ConsumePrescriptionRequest req = new ConsumePrescriptionRequest();
        req.setPrescriptionNo(prescriptionNo);
        req.setOrderNo(orderNo);
        req.setItems(items);
        return post("/api/consult/prescriptions/consume", req, PrescriptionDTO.class);
    }

    /** 补偿恢复处方（order-service 落单失败时调用，P1-01）：USED→ACTIVE，仅该订单消费的可恢复 */
    public PrescriptionDTO restorePrescription(String prescriptionNo, String orderNo) {
        RestorePrescriptionRequest req = new RestorePrescriptionRequest();
        req.setOrderNo(orderNo);
        return post("/api/consult/prescriptions/{no}/restore", req, PrescriptionDTO.class, prescriptionNo);
    }

    /** 按处方号查询（前端回显处方状态/明细用） */
    public PrescriptionDTO getPrescription(String prescriptionNo) {
        return get("/api/consult/prescriptions/{no}", PrescriptionDTO.class, prescriptionNo);
    }

    /* ---------- 通用请求封装 ---------- */

    private <T> T get(String uri, Class<T> type, Object... uriVars) {
        try {
            ResponseEntity<T> resp = restClient.get().uri(uri, uriVars).retrieve().toEntity(type);
            return resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("问诊服务不可用，请稍后重试");
        }
    }

    private <T> T post(String uri, Object body, Class<T> type, Object... uriVars) {
        try {
            ResponseEntity<T> resp = restClient.post()
                    .uri(uri, uriVars)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(type);
            return resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("问诊服务不可用，请稍后重试");
        }
    }

    private <T> List<T> getList(String uri, ParameterizedTypeReference<List<T>> type, Object... uriVars) {
        try {
            ResponseEntity<List<T>> resp = restClient.get().uri(uri, uriVars).retrieve().toEntity(type);
            return resp.getBody() == null ? List.of() : resp.getBody();
        } catch (RestClientResponseException ex) {
            throw ClientSupport.toBusinessException(ex);
        } catch (RestClientException ex) {
            throw new BusinessException("问诊服务不可用，请稍后重试");
        }
    }
}
