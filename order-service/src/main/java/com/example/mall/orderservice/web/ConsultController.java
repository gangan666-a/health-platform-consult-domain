package com.example.mall.orderservice.web;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.orderservice.client.ConsultClient;
import com.example.mall.orderservice.consult.ConsultSessionIndex;
import com.example.mall.orderservice.consult.ConsultSessionIndex.IndexedSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 问诊网页（BFF）：问诊聊天页 / 医生工作台。
 * <p>
 * 会话集合与 token 由 BFF 侧内存索引 {@link ConsultSessionIndex} 提供（consult-service 无会话列表
 * REST 接口）；索引缺失时回退调用问诊服务重签（用户 token 可重签 R-12；医生 token 仅接单时签发，
 * 索引丢失且会话已接单时无法重取，属 R-12 已知限制，页面会给出提示）。
 */
@Controller
public class ConsultController {

    private static final String DOCTOR_ID = "DOC001";
    private static final String DOCTOR_NAME = "王医生";

    private final ConsultClient consultClient;
    private final ConsultSessionIndex index;

    @Value("${mall.consult-service.ws-url}")
    private String wsUrl;

    public ConsultController(ConsultClient consultClient, ConsultSessionIndex index) {
        this.consultClient = consultClient;
        this.index = index;
    }

    /** 问诊聊天页：role=user 用户视角 / role=doctor 医生视角 */
    @GetMapping("/consult/{sessionId}")
    public String consultPage(@PathVariable Long sessionId,
                              @RequestParam(defaultValue = "user") String role,
                              Model model) {
        IndexedSession session = index.get(sessionId);
        if (session == null) {
            // 索引丢失（如重启后未复用创建）：尝试从问诊服务拉取会话元信息重建索引
            ConsultSessionDTO dto = consultClient.getSession(sessionId);
            index.upsert(sessionId, dto.getProductId(), dto.getProductName(), null);
            session = index.get(sessionId);
        }
        String token = resolveToken(sessionId, role);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("role", role);
        model.addAttribute("token", token == null ? "" : token);
        model.addAttribute("productName", session.getProductName());
        model.addAttribute("productId", session.getProductId());
        model.addAttribute("wsUrl", wsUrl);
        model.addAttribute("statusLabel", session.getStatus() == null ? "" : session.getStatus().getLabel());
        return "consult";
    }

    /** 医生工作台：展示全部问诊会话（来自 BFF 内存索引） */
    @GetMapping("/doctor")
    public String doctorConsole(Model model) {
        List<IndexedSession> sessions = index.listAll();
        model.addAttribute("sessions", sessions);
        return "doctor";
    }

    /** 医生接单：调问诊服务接单 → 索引记录医生 token → 进入医生视角聊天页 */
    @PostMapping("/doctor/{sessionId}/accept")
    public String accept(@PathVariable Long sessionId) {
        ConsultSessionDTO dto = consultClient.acceptSession(sessionId, DOCTOR_ID, DOCTOR_NAME);
        index.markAccepted(sessionId, dto.getDoctorId(), dto.getDoctorName(), dto.getToken());
        return "redirect:/consult/" + sessionId + "?role=doctor";
    }

    /** 按角色取 WS token：用户优先索引、缺失重签；医生缺失时尝试补接单（会话未接单才成功） */
    private String resolveToken(Long sessionId, String role) {
        IndexedSession session = index.get(sessionId);
        if ("doctor".equals(role)) {
            if (session != null && session.getDoctorToken() != null) {
                return session.getDoctorToken();
            }
            // 索引丢失且未接单：补一次接单拿医生 token；已接单则失败（R-12 限制，页面提示）
            try {
                ConsultSessionDTO dto = consultClient.acceptSession(sessionId, DOCTOR_ID, DOCTOR_NAME);
                index.markAccepted(sessionId, dto.getDoctorId(), dto.getDoctorName(), dto.getToken());
                return dto.getToken();
            } catch (BusinessException ex) {
                return null;
            }
        }
        if (session != null && session.getUserToken() != null) {
            return session.getUserToken();
        }
        // 用户 token 可重签（R-12）：断线/重启后重取
        ConsultSessionDTO dto = consultClient.refreshUserToken(sessionId);
        index.setUserToken(sessionId, dto.getToken());
        return dto.getToken();
    }
}
