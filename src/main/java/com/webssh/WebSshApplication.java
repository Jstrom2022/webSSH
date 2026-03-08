package com.webssh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Web SSH 应用的启动入口类。
 * <p>
 * 本类作为 Spring Boot 应用的引导类，负责启动嵌入式 Web 容器并加载所有自动配置的 Bean。
 * 使用 {@code @ConfigurationPropertiesScan} 是为了自动扫描并注册带有
 * {@code @ConfigurationProperties} 的配置类（如 {@code WebSshAuthProperties}、
 * {@code SshCompatibilityProperties}），使得 application.yml 中的自定义配置能够绑定到 Java 对象。
 * </p>
 *
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 * @see org.springframework.boot.context.properties.ConfigurationPropertiesScan
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WebSshApplication {

    /**
     * 应用主入口方法。
     * <p>
     * 启动 Spring Boot 应用，初始化 IoC 容器、加载配置、注册 Bean，并启动内嵌的 Tomcat 服务器。
     * 之所以将启动逻辑放在独立的 main 方法中，是为了符合 Java 应用的标准启动方式，
     * 便于通过 {@code java -jar} 或 IDE 直接运行。
     * </p>
     *
     * @param args 命令行参数，可传递给 Spring 环境（如 {@code --server.port=8080}）
     */
    public static void main(String[] args) {
        SpringApplication.run(WebSshApplication.class, args);
    }
}
