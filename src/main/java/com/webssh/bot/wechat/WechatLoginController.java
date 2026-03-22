package com.webssh.bot.wechat;

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微信 QClaw 扫码登录控制器。
 * <p>
 * 提供两个端点：获取二维码 URL 和用授权码换取 Token，
 * 使前端可直接在 WebSSH 界面内完成微信扫码登录流程。
 * </p>
 */
@RestController
@RequestMapping("/api/wechat-login")
public class WechatLoginController {

    private static final Logger log = LoggerFactory.getLogger(WechatLoginController.class);

    private static final String ENDPOINT_LOGIN_STATE = "data/4050/forward";
    private static final String ENDPOINT_WX_LOGIN = "data/4026/forward";

    private static final String WX_APPID = "wx9d11056dd75b7240";
    private static final String WX_REDIRECT_URI = "https://security.guanjia.qq.com/login";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WechatLoginController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 获取微信扫码登录的二维码 URL。
     * <p>
     * 调用 QClaw data/4050/forward 获取 CSRF state，拼接微信 OAuth 二维码地址返回前端。
     * </p>
     */
    @PostMapping("/qr-url")
    public ResponseEntity<Map<String, Object>> getQrUrl() {
        Map<String, Object> result = new LinkedHashMap<>();
        String guid = UUID.randomUUID().toString();

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("guid", guid);
            body.put("web_version", WechatBotProvider.WEB_VERSION);
            body.put("web_env", "release");

            HttpRequest request = buildJprxRequest(
                    WechatBotProvider.JPRX_GATEWAY + ENDPOINT_LOGIN_STATE,
                    body, guid, null);

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.put("success", false);
                result.put("message", "QClaw 返回 HTTP " + response.statusCode());
                return ResponseEntity.ok(result);
            }

            JsonNode json = objectMapper.readTree(response.body());
            log.debug("微信登录 state 原始响应: {}", response.body());
            JsonNode data = WechatBotProvider.unwrapData(json);

            String state = data != null ? data.path("state").asText(null) : null;
            if (state == null || state.isEmpty()) {
                log.warn("未获取到登录 state, 解包后数据: {}", data);
                result.put("success", false);
                result.put("message", "未获取到登录 state");
                return ResponseEntity.ok(result);
            }

            String qrUrl = "https://open.weixin.qq.com/connect/qrconnect"
                    + "?appid=" + WX_APPID
                    + "&redirect_uri=" + URLEncoder.encode(WX_REDIRECT_URI, StandardCharsets.UTF_8)
                    + "&response_type=code"
                    + "&scope=snsapi_login"
                    + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                    + "#wechat_redirect";

            result.put("success", true);
            result.put("qrUrl", qrUrl);
            result.put("state", state);
            result.put("guid", guid);

        } catch (Exception e) {
            log.error("获取微信扫码二维码失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "获取二维码失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 用微信授权码换取 channelToken 和 jwtToken。
     */
    @PostMapping("/exchange")
    public ResponseEntity<Map<String, Object>> exchangeToken(
            @RequestBody Map<String, String> payload) {
        Map<String, Object> result = new LinkedHashMap<>();

        String guid = payload.get("guid");
        String code = payload.get("code");
        String state = payload.get("state");

        if (code == null || code.isBlank()) {
            result.put("success", false);
            result.put("message", "缺少 code 参数");
            return ResponseEntity.ok(result);
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("guid", guid);
            body.put("code", code);
            body.put("state", state);
            body.put("web_version", WechatBotProvider.WEB_VERSION);
            body.put("web_env", "release");

            HttpRequest request = buildJprxRequest(
                    WechatBotProvider.JPRX_GATEWAY + ENDPOINT_WX_LOGIN,
                    body, guid, null);

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.put("success", false);
                result.put("message", "QClaw 返回 HTTP " + response.statusCode());
                return ResponseEntity.ok(result);
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode data = WechatBotProvider.unwrapData(json);

            String channelToken = data != null
                    ? data.path("openclaw_channel_token").asText(null)
                    : null;

            // jwtToken 可能在响应 header 或 data 中
            String jwtToken = response.headers()
                    .firstValue("X-New-Token").orElse(null);
            if (jwtToken == null && data != null) {
                jwtToken = data.path("token").asText(null);
                if (jwtToken == null) {
                    jwtToken = data.path("jwt_token").asText(null);
                }
            }

            if (channelToken == null || channelToken.isEmpty()) {
                result.put("success", false);
                result.put("message", "未获取到 channelToken，请确认已完成微信授权");
                return ResponseEntity.ok(result);
            }

            result.put("success", true);
            result.put("channelToken", channelToken);
            if (jwtToken != null && !jwtToken.isEmpty()) {
                result.put("jwtToken", jwtToken);
            }

        } catch (Exception e) {
            log.error("微信扫码换取 Token 失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "换取 Token 失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /** 构建 jprx 网关请求，复用 WechatBotProvider 中的 header 模式。 */
    private HttpRequest buildJprxRequest(String url, Map<String, Object> body,
            String guid, String openClawToken) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Version", "1");
        headers.put("X-Token", guid != null ? guid : WechatBotProvider.DEFAULT_LOGIN_KEY);
        headers.put("X-Guid", guid != null ? guid : "1");
        headers.put("X-Account", "1");
        headers.put("X-Session", "");
        if (openClawToken != null) {
            headers.put("X-OpenClaw-Token", openClawToken);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)));
        headers.forEach(builder::header);

        return builder.build();
    }
}
