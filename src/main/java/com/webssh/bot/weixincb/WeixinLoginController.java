package com.webssh.bot.weixincb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信 ClawBot iLink 扫码登录控制器。
 * <p>
 * 提供 QR 二维码获取和扫码状态轮询端点，
 * 前端自动轮询无需用户粘贴 URL。
 * </p>
 */
@RestController
@RequestMapping("/api/weixin-clawbot-login")
public class WeixinLoginController {

    private static final Logger log = LoggerFactory.getLogger(WeixinLoginController.class);
    private static final String DEFAULT_BOT_TYPE = "3";
    private static final long QR_POLL_TIMEOUT_MS = 35_000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** sessionId → qrcode 映射，用于轮询状态。 */
    private final ConcurrentHashMap<String, QrSession> activeSessions = new ConcurrentHashMap<>();

    record QrSession(String qrcode, String baseUrl, long createdAt) {}

    public WeixinLoginController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 获取微信 ClawBot 扫码二维码。
     */
    @PostMapping("/qr-start")
    public ResponseEntity<Map<String, Object>> qrStart(
            @RequestBody(required = false) Map<String, String> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        String baseUrl = WeixinClawBotProvider.DEFAULT_BASE_URL;
        if (payload != null && payload.get("baseUrl") != null && !payload.get("baseUrl").isBlank()) {
            baseUrl = payload.get("baseUrl").trim();
        }

        try {
            String url = baseUrl;
            if (!url.endsWith("/")) url += "/";
            url += "ilink/bot/get_bot_qrcode?bot_type=" + DEFAULT_BOT_TYPE;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.put("success", false);
                result.put("message", "iLink 返回 HTTP " + response.statusCode());
                return ResponseEntity.ok(result);
            }

            JsonNode json = objectMapper.readTree(response.body());
            String qrcode = json.path("qrcode").asText(null);
            String qrcodeUrl = json.path("qrcode_img_content").asText(null);

            if (qrcode == null || qrcode.isEmpty()) {
                result.put("success", false);
                result.put("message", "未获取到二维码");
                return ResponseEntity.ok(result);
            }

            String sessionId = UUID.randomUUID().toString();
            activeSessions.put(sessionId, new QrSession(qrcode, baseUrl, System.currentTimeMillis()));
            purgeExpired();

            result.put("success", true);
            result.put("sessionId", sessionId);
            result.put("qrcodeUrl", qrcodeUrl != null ? qrcodeUrl : qrcode);

        } catch (Exception e) {
            log.error("微信 ClawBot 获取二维码失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取二维码失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 轮询扫码状态。前端每 2-3 秒调用一次。
     */
    @PostMapping("/qr-poll")
    public ResponseEntity<Map<String, Object>> qrPoll(
            @RequestBody Map<String, String> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        String sessionId = payload.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            result.put("success", false);
            result.put("message", "缺少 sessionId");
            return ResponseEntity.ok(result);
        }

        QrSession session = activeSessions.get(sessionId);
        if (session == null) {
            result.put("success", false);
            result.put("status", "expired");
            result.put("message", "会话不存在或已过期");
            return ResponseEntity.ok(result);
        }

        try {
            String url = session.baseUrl();
            if (!url.endsWith("/")) url += "/";
            url += "ilink/bot/get_qrcode_status?qrcode=" + java.net.URLEncoder.encode(
                    session.qrcode(), java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(QR_POLL_TIMEOUT_MS))
                    .GET()
                    .header("iLink-App-ClientVersion", "1")
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.put("success", true);
                result.put("status", "wait");
                return ResponseEntity.ok(result);
            }

            JsonNode json = objectMapper.readTree(response.body());
            String status = json.path("status").asText("wait");

            result.put("success", true);
            result.put("status", status);

            if ("confirmed".equals(status)) {
                String botToken = json.path("bot_token").asText(null);
                String returnedBaseUrl = json.path("baseurl").asText(null);
                String userId = json.path("ilink_user_id").asText(null);

                activeSessions.remove(sessionId);

                if (botToken != null) result.put("botToken", botToken);
                if (returnedBaseUrl != null && !returnedBaseUrl.isBlank()) {
                    result.put("baseUrl", returnedBaseUrl);
                }
                if (userId != null) result.put("userId", userId);
                result.put("message", "与微信连接成功！");
            } else if ("scaned".equals(status)) {
                result.put("message", "已扫码，请在微信中确认...");
            } else if ("expired".equals(status)) {
                activeSessions.remove(sessionId);
                result.put("message", "二维码已过期，请重新获取。");
            }

        } catch (java.net.http.HttpTimeoutException e) {
            // 长轮询超时是正常的
            result.put("success", true);
            result.put("status", "wait");
        } catch (Exception e) {
            log.error("微信 ClawBot 轮询状态失败: {}", e.getMessage());
            result.put("success", true);
            result.put("status", "wait");
        }

        return ResponseEntity.ok(result);
    }

    /** 清理超过 5 分钟的过期会话。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> now - e.getValue().createdAt() > 5 * 60_000);
    }
}
