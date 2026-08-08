package com.example.mall.consultservice.controller;

import com.example.mall.common.model.ChatMessageDTO;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsumePrescriptionRequest;
import com.example.mall.common.model.CreateConsultSessionRequest;
import com.example.mall.common.model.PrescriptionDTO;
import com.example.mall.common.model.RestorePrescriptionRequest;
import com.example.mall.common.model.WritePrescriptionRequest;
import com.example.mall.consultservice.service.ConsultationSessionService;
import com.example.mall.consultservice.service.MessageService;
import com.example.mall.consultservice.service.PrescriptionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问诊 REST（:8085，Spring MVC）。契约供 order-service BFF 集成（F5）：
 * 会话（创建/复用、详情、接单、结束、消息历史、token 重签）+ 处方（开具、一次性消费、补偿恢复、查询）。
 */
@RestController
@RequestMapping("/api/consult")
public class ConsultationController {

    private final ConsultationSessionService sessionService;
    private final MessageService messageService;
    private final PrescriptionService prescriptionService;

    public ConsultationController(ConsultationSessionService sessionService,
                                  MessageService messageService,
                                  PrescriptionService prescriptionService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.prescriptionService = prescriptionService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AcceptSessionRequest(String doctorId, String doctorName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndSessionRequest(String reason) {
    }

    /** 创建/复用会话：返回用户 WS token（同药品未结束会话复用并重发 token） */
    @PostMapping("/sessions")
    public ConsultSessionDTO create(@RequestBody CreateConsultSessionRequest req) {
        return sessionService.createOrReuse(req);
    }

    @GetMapping("/sessions/{id}")
    public ConsultSessionDTO detail(@PathVariable Long id) {
        return sessionService.getSession(id);
    }

    /** 医生接单：WAITING→ONGOING，返回医生 WS token */
    @PostMapping("/sessions/{id}/accept")
    public ConsultSessionDTO accept(@PathVariable Long id,
                                    @RequestBody(required = false) AcceptSessionRequest req) {
        return sessionService.accept(id, req == null ? null : req.doctorId(),
                req == null ? null : req.doctorName());
    }

    /** 主动结束（reason 默认 USER_ENDED；医生侧传 DOCTOR_ENDED），幂等 */
    @PostMapping("/sessions/{id}/end")
    public ConsultSessionDTO end(@PathVariable Long id,
                                 @RequestBody(required = false) EndSessionRequest req) {
        String reason = (req == null || req.reason() == null || req.reason().isBlank())
                ? "USER_ENDED" : req.reason();
        return sessionService.end(id, reason);
    }

    /** 消息历史（afterSeq 增量补拉，断线重连用）；先 flush 写队列保证读后写一致 */
    @GetMapping("/sessions/{id}/messages")
    public List<ChatMessageDTO> messages(@PathVariable Long id,
                                         @RequestParam(required = false) Integer afterSeq) {
        return messageService.history(id, afterSeq);
    }

    /** 重新签发用户 token（前端断线重连取 token） */
    @PostMapping("/sessions/{id}/ws-token")
    public ConsultSessionDTO wsToken(@PathVariable Long id) {
        return sessionService.refreshToken(id);
    }

    /** 医生开方：ONGOING→PRESCRIPTION_ISSUED（同事务），返回处方 DTO */
    @PostMapping("/prescriptions")
    public PrescriptionDTO prescribe(@RequestBody WritePrescriptionRequest req) {
        return prescriptionService.prescribe(req);
    }

    /** 一次性消费（order-service 下单时调用，P1-02 必须携带 orderNo） */
    @PostMapping("/prescriptions/consume")
    public PrescriptionDTO consume(@RequestBody ConsumePrescriptionRequest req) {
        return prescriptionService.consume(req);
    }

    /** 补偿恢复（order-service 落单失败时调用，P1-01）：USED→ACTIVE */
    @PostMapping("/prescriptions/{no}/restore")
    public PrescriptionDTO restore(@PathVariable String no,
                                   @RequestBody(required = false) RestorePrescriptionRequest req) {
        return prescriptionService.restore(no, req == null ? null : req.getOrderNo());
    }

    @GetMapping("/prescriptions/{no}")
    public PrescriptionDTO prescription(@PathVariable String no) {
        return prescriptionService.getByNo(no);
    }

    /** 按会话查处方列表（开方历史展示） */
    @GetMapping("/sessions/{id}/prescriptions")
    public List<PrescriptionDTO> prescriptions(@PathVariable Long id) {
        return prescriptionService.listBySession(id);
    }
}
