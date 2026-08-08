package com.example.mall.orderservice.web;

import com.example.mall.common.exception.BusinessException;
import com.example.mall.common.model.ConsultSessionDTO;
import com.example.mall.common.model.ConsultationStatus;
import com.example.mall.orderservice.client.ConsultClient;
import com.example.mall.orderservice.consult.ConsultSessionIndex;
import com.example.mall.orderservice.consult.ConsultSessionIndex.IndexedSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问诊网页路由测试：问诊聊天页（user/doctor）、医生工作台、医生接单。
 * 覆盖 BFF 索引 → 页面渲染 / 用户 token 重签兜底 / 接单后进入医生视角。
 */
@WebMvcTest(ConsultController.class)
class ConsultControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConsultClient consultClient;

    @MockBean
    private ConsultSessionIndex index;

    private IndexedSession indexedSession() {
        IndexedSession s = new IndexedSession();
        s.setSessionId(1L);
        s.setProductId(7L);
        s.setProductName("布洛芬");
        s.setUserToken("token-user-1");
        s.setStatus(ConsultationStatus.ONGOING);
        return s;
    }

    @Test
    void consultPage_user_rendersChat() throws Exception {
        when(index.get(1L)).thenReturn(indexedSession());

        mockMvc.perform(get("/consult/1").param("role", "user"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("布洛芬")))
                .andExpect(content().string(containsString("在线问诊")));
    }

    /** 索引缺失（如重启后）→ 从问诊服务拉元信息重建索引 + 重签用户 token（R-12） */
    @Test
    void consultPage_user_indexMissing_refreshesTokenAndRebuilds() throws Exception {
        // 首次 get → null（触发重建）；重建后 get → 带元信息的会话（userToken 为空 → 触发重签）
        IndexedSession rebuilt = new IndexedSession();
        rebuilt.setSessionId(1L);
        rebuilt.setProductId(7L);
        rebuilt.setProductName("布洛芬");
        when(index.get(1L)).thenReturn(null, rebuilt);
        ConsultSessionDTO meta = new ConsultSessionDTO();
        meta.setSessionId(1L);
        meta.setProductId(7L);
        meta.setProductName("布洛芬");
        when(consultClient.getSession(1L)).thenReturn(meta);
        ConsultSessionDTO tokenDto = new ConsultSessionDTO();
        tokenDto.setSessionId(1L);
        tokenDto.setToken("token-refreshed");
        when(consultClient.refreshUserToken(1L)).thenReturn(tokenDto);

        mockMvc.perform(get("/consult/1").param("role", "user"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("布洛芬")));

        verify(index).upsert(1L, 7L, "布洛芬", null);
        verify(index).setUserToken(1L, "token-refreshed");
    }

    /** 用户视角：渲染会话信息（药品名/状态）与消息区（chat-window） */
    @Test
    void consultPage_user_rendersSessionInfoAndChatArea() throws Exception {
        when(index.get(1L)).thenReturn(indexedSession());

        mockMvc.perform(get("/consult/1").param("role", "user"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("在线问诊")))
                .andExpect(content().string(containsString("布洛芬")))
                .andExpect(content().string(containsString("chat-window")))
                .andExpect(content().string(containsString("医生接入后将在此聊天")));
    }

    /** 医生视角：渲染开方面板（doctor token 已登记，直接复用不补接单） */
    @Test
    void consultPage_doctorRole_rendersPrescribePanel() throws Exception {
        IndexedSession s = indexedSession();
        s.setDoctorId("DOC001");
        s.setDoctorName("王医生");
        s.setDoctorToken("token-doctor-1");
        when(index.get(1L)).thenReturn(s);

        mockMvc.perform(get("/consult/1").param("role", "doctor"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("在线问诊")))
                .andExpect(content().string(containsString("开具处方")));

        // 医生 token 已存在，不应触发补接单
        verify(consultClient, never()).acceptSession(1L, "DOC001", "王医生");
    }

    /** 会话不存在（索引缺失且问诊服务也查不到）→ 业务错误页（AC-IM-6 fail-closed，不白屏） */
    @Test
    void consultPage_sessionNotFound_rendersErrorPage() throws Exception {
        when(index.get(1L)).thenReturn(null);
        when(consultClient.getSession(1L))
                .thenThrow(new BusinessException("会话不存在"));

        mockMvc.perform(get("/consult/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("出错了")))
                .andExpect(content().string(containsString("会话不存在")));
    }

    @Test
    void doctorConsole_listsSessions() throws Exception {
        when(index.listAll()).thenReturn(List.of(indexedSession()));

        mockMvc.perform(get("/doctor"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("医生工作台")))
                .andExpect(content().string(containsString("布洛芬")));
    }

    @Test
    void doctorAccept_redirectsToDoctorChat() throws Exception {
        ConsultSessionDTO dto = new ConsultSessionDTO();
        dto.setSessionId(1L);
        dto.setDoctorId("DOC001");
        dto.setDoctorName("王医生");
        dto.setToken("token-doctor-1");
        when(consultClient.acceptSession(1L, "DOC001", "王医生")).thenReturn(dto);

        mockMvc.perform(post("/doctor/1/accept"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/consult/1?role=doctor"));

        verify(index).markAccepted(1L, "DOC001", "王医生", "token-doctor-1");
    }
}
