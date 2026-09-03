package com.example.mall.agentservice.eval;

import com.example.mall.agentservice.report.ReportParseResult;
import com.example.mall.agentservice.report.ReportParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉抽取质量门禁（env-gated）：设置 VISION_API_KEY（智谱）后启用，
 * 用渲染的化验单图片跑真实 GLM-4.6V-Flash 抽取，字段级 recall 门槛 0.90——
 ** 低于阈值即失败并提示把 agent.report.vision.model 切换为付费档 glm-4.6v（¥1/¥3 每百万）。
 * 未设 Key 干净跳过，CI 不受影响。
 */
@SpringBootTest(properties = {
        "agent.report.vision.api-key=${VISION_API_KEY}",
        "agent.report.vision.model=glm-4.6v-flash"
})
@EnabledIfEnvironmentVariable(named = "VISION_API_KEY", matches = ".+")
class VisionEvalTest {

    private static final double GATE = 0.90;

    @Autowired
    ReportParser parser;

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void visionExtraction_recallMeetsGate_otherwiseSwitchToPaidModel() throws Exception {
        byte[] png = renderReportImage(
                "#shenghua",
                "WBC 6.5 10^9/L 3.5-9.5",
                "HGB 138 g/L 115-150",
                "ALT 42 U/L 7-40");

        ReportParseResult result = parser.parse(png, "xuechanggui.png", "image/png");

        List<String> expected = List.of("WBC=6.5", "HGB=138", "ALT=42");
        List<String> actual = result.doc().indicators().stream()
                .map(i -> i.name() + "=" + i.value())
                .toList();
        long matched = expected.stream().filter(actual::contains).count();
        double recall = (double) matched / expected.size();

        assertTrue(recall >= GATE, () -> "视觉抽取 recall=" + recall + "（实际: " + actual
                + "）低于 0.90 门禁：请将 agent.report.vision.model 切换为付费档 glm-4.6v 后重跑");
        assertTrue(result.trace().stream().noneMatch(t -> t.contains("vision-unavailable")),
                "视觉通道未生效（检查 agent.report.vision.api-key）");
    }

    /** 渲染白底黑字的化验单图片（模拟拍照/截图件）。 */
    private static byte[] renderReportImage(String... lines) throws Exception {
        BufferedImage image = new BufferedImage(1200, 400 + lines.length * 70, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 40));
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], 80, 120 + i * 70);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
