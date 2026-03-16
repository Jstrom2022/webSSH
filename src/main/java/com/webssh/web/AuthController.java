package com.webssh.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

/**
 * 认证 API 控制器，提供与当前登录用户相关的 REST 接口。
 * <p>
 * 前端需要获取当前登录用户信息时调用此类接口。Principal 由 Spring Security 在认证成功后注入，
 * 若请求未认证则 principal 为 null，此时会由 Security 配置拦截并返回 401。
 * </p>
 */
@RestController
public class AuthController {

    /**
     * 获取当前登录用户的基本信息。
     * <p>
     * 用于前端判断登录状态、展示用户名等。返回 Map 而非自定义 DTO 是为了保持接口简洁，
     * 且当前仅需 username 一个字段，后续如需扩展可再引入 DTO。
     * </p>
     *
     * @param principal 由 Spring Security 注入的当前认证主体，未登录时为 null（会被 Security 拦截）
     * @return 包含 username 键的 Map，值为当前登录用户名
     */
    @GetMapping("/api/auth/me")
    public Map<String, String> me(Principal principal) {
        // 该接口由认证过滤链保护，走到这里时 principal 理论上一定非空。
        return Map.of("username", principal.getName());
    }
}
