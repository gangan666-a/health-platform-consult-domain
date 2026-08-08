package com.example.mall.orderservice.consult;

import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsultationStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BFF 侧问诊会话索引（内存）。
 * <p>
 * consult-service 未提供「会话列表」REST 接口（仅 WS 实时推送），医生工作台 / 问诊页刷新需要
 * 会话集合与各类 token。order-service 作为 BFF，在创建会话（user）与接单（doctor）时登记索引：
 * 关键帧（joined/prescription_issued/session_ended）经 WS 回调实时回写，断电重启后由
 * 会话创建接口（同药品复用）重新登记。演示环境内存态可接受（R-12 语义对齐 consult-service）。
 */
@Component
public class ConsultSessionIndex {

    /** 会话索引：sessionId → 会话条目 */
    private final Map<Long, IndexedSession> sessions = new ConcurrentHashMap<>();

    /** 记录一次会话创建/复用（user token 由 ConsultClient.createSession 返回） */
    public void upsert(Long sessionId, Long productId, String productName, String userToken) {
        IndexedSession s = sessions.computeIfAbsent(sessionId, id -> {
            IndexedSession n = new IndexedSession();
            n.setSessionId(id);
            n.setCreatedAt(System.currentTimeMillis());
            return n;
        });
        s.setProductId(productId);
        s.setProductName(productName);
        s.setStatus(ConsultationStatus.WAITING);
        if (userToken != null) {
            s.setUserToken(userToken);
        }
    }

    /** 医生接单后登记：绑定医生 + 更新状态（doctorToken 由 accept 返回） */
    public void markAccepted(Long sessionId, String doctorId, String doctorName, String doctorToken) {
        IndexedSession s = sessions.get(sessionId);
        if (s == null) {
            s = new IndexedSession();
            s.setSessionId(sessionId);
            s.setCreatedAt(System.currentTimeMillis());
            sessions.put(sessionId, s);
        }
        s.setDoctorId(doctorId);
        s.setDoctorName(doctorName);
        s.setStatus(ConsultationStatus.ONGOING);
        if (doctorToken != null) {
            s.setDoctorToken(doctorToken);
        }
    }

    /** 回写 token（创建/重签后更新 user token，避免断线重连拿到旧值） */
    public void setUserToken(Long sessionId, String userToken) {
        IndexedSession s = sessions.get(sessionId);
        if (s != null && userToken != null) {
            s.setUserToken(userToken);
        }
    }

    /** 状态回写（prescription_issued / session_ended 等关键帧） */
    public void updateStatus(Long sessionId, ConsultationStatus status) {
        IndexedSession s = sessions.get(sessionId);
        if (s != null && status != null) {
            s.setStatus(status);
        }
    }

    public IndexedSession get(Long sessionId) {
        return sessions.get(sessionId);
    }

    /** 医生工作台：按创建时间倒序的全部会话 */
    public List<IndexedSession> listAll() {
        List<IndexedSession> list = new ArrayList<>(sessions.values());
        list.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return list;
    }

    /** 会话条目（BFF 侧视图对象） */
    public static class IndexedSession {
        private Long sessionId;
        private Long productId;
        private String productName;
        private ConsultationStatus status;
        private String doctorId;
        private String doctorName;
        private String userToken;
        private String doctorToken;
        private long createdAt;

        public Long getSessionId() {
            return sessionId;
        }

        public void setSessionId(Long sessionId) {
            this.sessionId = sessionId;
        }

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public ConsultationStatus getStatus() {
            return status;
        }

        public void setStatus(ConsultationStatus status) {
            this.status = status;
        }

        public String getDoctorId() {
            return doctorId;
        }

        public void setDoctorId(String doctorId) {
            this.doctorId = doctorId;
        }

        public String getDoctorName() {
            return doctorName;
        }

        public void setDoctorName(String doctorName) {
            this.doctorName = doctorName;
        }

        public String getUserToken() {
            return userToken;
        }

        public void setUserToken(String userToken) {
            this.userToken = userToken;
        }

        public String getDoctorToken() {
            return doctorToken;
        }

        public void setDoctorToken(String doctorToken) {
            this.doctorToken = doctorToken;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
    }
}
