package com.wildfirechat.pan.filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 两个过滤器都拦截所有路径，各自只处理自己负责的端口（见 shouldNotFilter），
 * 保证任何请求都至少经过一个过滤器的访问控制。
 */
@Configuration
public class PortBasedFilterConfig {

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(AdminAuthFilter filter) {
        FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setName("adminAuthFilter");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ClientAuthFilter> clientAuthFilterRegistration(ClientAuthFilter filter) {
        FilterRegistrationBean<ClientAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setName("clientAuthFilter");
        registration.setOrder(2);
        return registration;
    }
}
