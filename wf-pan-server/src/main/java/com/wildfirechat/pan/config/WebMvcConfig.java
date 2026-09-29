package com.wildfirechat.pan.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${server.admin-port:8080}")
    private int adminPort;

    /**
     * 管理端口监听地址，为空时监听所有网卡。部署在 nginx 后面时建议设为 127.0.0.1
     */
    @Value("${server.admin-address:}")
    private String adminAddress;

    /**
     * 只有客户端接口需要跨域（authCode 放在 header 中，不依赖 cookie）；管理后台与接口同源
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
            .allowedOrigins("*")
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);
    }

    /**
     * 配置双端口：server.port（客户端）+ server.admin-port（管理）
     */
    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        tomcat.addAdditionalTomcatConnectors(createAdminConnector());
        return tomcat;
    }

    private Connector createAdminConnector() {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(adminPort);
        if (StringUtils.hasText(adminAddress)) {
            connector.setProperty("address", adminAddress);
        }
        return connector;
    }
}
