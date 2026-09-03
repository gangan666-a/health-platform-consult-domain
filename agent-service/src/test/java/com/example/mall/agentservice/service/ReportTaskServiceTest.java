package com.example.mall.agentservice.service;

import com.example.mall.agentservice.dto.ReportUploadResponse;
import com.example.mall.agentservice.report.PdfBoxToolTest;
import com.example.mall.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步报告任务：提交即受理（consent/文件校验同步）→ 虚拟线程执行 → 轮询到终态取结果。
 * 进度事件贯穿 PROFILE/PLAN/CLASSIFY/EXTRACT/.../DONE（SSE 推送复用同一事件流）。
 */
@SpringBootTest
class ReportTaskServiceTest {

    @Autowired
    ReportTaskService taskService;

    @Test
    void submit_completesWithResultAndProgressEvents() {
        MockMultipartFile file = labPdf();

        ReportTaskService.TaskAccepted accepted = taskService.submit(2001L, "sess-task", file, true);

        assertNotNull(accepted.taskId());
        // 虚拟线程启动极快，受理时可能已进入 RUNNING——只断言已入队执行
        assertTrue(accepted.status().equals("QUEUED") || accepted.status().equals("RUNNING"));
        assertTrue(accepted.pollUrl().contains(accepted.taskId()));

        ReportTaskService.ReportTaskView view = await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> taskService.view(accepted.taskId()), v -> terminal(v.status()));

        assertEquals("DONE", view.status());
        assertNotNull(view.result());
        assertEquals("xuechanggui", view.result().examName());
        assertFalse(view.result().indicators().isEmpty());
        assertFalse(view.events().isEmpty());
        assertTrue(view.events().stream().anyMatch(e -> "PROFILE".equals(e.stage())));
        assertTrue(view.events().stream().anyMatch(e -> "DONE".equals(e.stage())));
    }

    @Test
    void submit_withoutConsent_rejectedImmediately() {
        assertThrows(BusinessException.class,
                () -> taskService.submit(2002L, null, labPdf(), false));
    }

    @Test
    void view_unknownTask_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> taskService.view("no-such-task"));
        assertTrue(ex.getMessage().contains("任务不存在"));
    }

    private static boolean terminal(String status) {
        return "DONE".equals(status) || "FAILED".equals(status);
    }

    private static MockMultipartFile labPdf() {
        String text = "#xuechanggui\nWBC 6.5 10^9/L 3.5-9.5\nHGB 138 g/L 115-150";
        return new MockMultipartFile("file", "xuechanggui.pdf", "application/pdf",
                PdfBoxToolTest.pdfWithLine(text));
    }
}
