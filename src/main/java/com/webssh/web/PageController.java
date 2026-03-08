package com.webssh.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器，负责将 URL 路径映射到静态 HTML 页面。
 * <p>
 * 使用 {@code @Controller} 而非 {@code @RestController}，因为返回的是视图名称（用于转发），
 * 而非 JSON 数据。这样 Spring 会将其解析为内部转发请求，保持 URL 不变，用户看到的是 /login，
 * 实际渲染的是 login.html 的内容。
 * </p>
 */
@Controller
public class PageController {

    /**
     * 处理登录页面的请求，将 /login 路径转发到 login.html 静态页面。
     * <p>
     * 使用 {@code forward:} 而非 {@code redirect:} 的原因：转发是服务器内部跳转，
     * 浏览器地址栏保持 /login 不变，避免暴露实际静态资源路径，同时保持 RESTful 风格的 URL。
     * </p>
     *
     * @return 视图名称 "forward:/login.html"，指示 Spring 进行内部转发
     */
    @GetMapping("/login")
    public String loginPage() {
        return "forward:/login.html";
    }
}
