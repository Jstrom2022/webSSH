package com.webssh.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 安全配置类。
 * <p>
 * 负责定义 Web 应用的安全策略：哪些路径需要认证、登录/登出行为、以及用户来源。
 * 本应用采用基于表单的登录方式，使用内存中的用户存储，适用于单用户或轻量级部署场景。
 * 若需支持多用户或持久化存储，可替换 {@link UserDetailsService} 实现。
 * </p>
 *
 * @see WebSshAuthProperties 登录凭据的配置来源
 */
@Configuration
public class SecurityConfig {

    /**
     * 配置 HTTP 安全过滤链。
     * <p>
     * 定义请求的认证规则和登录/登出行为。禁用 CSRF 是因为本应用主要提供 WebSocket SSH 终端，
     * 与传统的表单提交场景不同，且简化了与前端 WebSocket 的集成；若将来增加敏感表单操作，
     * 建议重新评估 CSRF 策略。
     * </p>
     *
     * @param http Spring Security 的 HTTP 安全构建器
     * @return 构建完成的安全过滤链
     * @throws Exception 配置过程中的异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用 CSRF，便于 WebSocket 等非表单场景；生产环境可根据需要开启
                .csrf(csrf -> csrf.disable())
                // 登录页及静态资源放行，其余请求需认证
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/login.html", "/login.css", "/i18n.js").permitAll()
                        .anyRequest().authenticated()
                )
                // 使用自定义登录页，登录成功后重定向到首页
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll()
                )
                // 登出后跳转回登录页并附带 logout 参数，便于前端显示提示
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );

        return http.build();
    }

    /**
     * 提供基于内存的用户详情服务。
     * <p>
     * 从 {@link WebSshAuthProperties} 读取用户名和密码，使用 BCrypt 编码后存入内存。
     * 采用内存存储是为了简化部署，无需数据库；密码必须经过编码器加密，避免明文存储带来的安全风险。
     * </p>
     *
     * @param authProperties 认证配置（用户名、密码）
     * @param passwordEncoder 密码编码器，用于对明文密码进行哈希
     * @return 内存用户管理器实例
     */
    @Bean
    public UserDetailsService userDetailsService(WebSshAuthProperties authProperties,
                                                 PasswordEncoder passwordEncoder) {
        UserDetails user = User.withUsername(authProperties.getUsername())
                .password(passwordEncoder.encode(authProperties.getPassword()))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    /**
     * 提供 BCrypt 密码编码器。
     * <p>
     * 选择 BCrypt 是因为其内置盐值、抗暴力破解，且被 Spring Security 推荐为默认算法。
     * 每次编码同一密码会得到不同结果，避免彩虹表攻击。
     * </p>
     *
     * @return BCrypt 编码器实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
