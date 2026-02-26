package com.wildfirechat.pan.filter;

import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PortBasedFilterConfig {
    
    /**
     * 管理端口认证过滤器（8080）
     */
    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(AdminAuthFilter filter) {
        FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/admin/*");
        registration.setName("adminAuthFilter");
        registration.setOrder(1);
        return registration;
    }
    
    /**
     * 客户端认证过滤器（8081）
     */
    @Bean
    public FilterRegistrationBean<ClientAuthFilter> clientAuthFilterRegistration(ClientAuthFilter filter) {
        FilterRegistrationBean<ClientAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/*");
        registration.setName("clientAuthFilter");
        registration.setOrder(2);
        return registration;
    }
}
