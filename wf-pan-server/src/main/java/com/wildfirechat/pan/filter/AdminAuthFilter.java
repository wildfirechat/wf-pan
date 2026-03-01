package com.wildfirechat.pan.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wildfirechat.pan.dto.Result;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class AdminAuthFilter implements Filter {
    @Value("${server.admin-port:8080}")
    private int admin_port;

    public static final String ADMIN_SESSION_KEY = "admin_user";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // 获取请求端口
        int serverPort = request.getLocalPort();
        String uri = httpRequest.getRequestURI();
        
        // 严格端口隔离：管理端口禁止访问客户端 API
        if (serverPort == admin_port) {
            if (isClientApi(uri)) {
                log.warn("Admin port {} attempted to access client API: {}", serverPort, uri);
                writeErrorResponse(httpResponse, 403, "客户端接口不允许从管理端口访问");
                return;
            }
        }
        
        // 只允许管理端口的请求进入管理接口
        if (serverPort != admin_port) {
            chain.doFilter(request, response);
            return;
        }
        
        // 登录相关接口放行
        if (uri.equals("/api/auth/login") || uri.equals("/api/auth/status")) {
            chain.doFilter(request, response);
            return;
        }
        
        // 静态资源放行（assets目录下的文件）
        if (uri.startsWith("/assets/")) {
            chain.doFilter(request, response);
            return;
        }
        
        // 前端页面和路由放行（让前端处理权限判断）
        // API 接口需要认证，其他都放行
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        
        // 检查 session
        HttpSession session = httpRequest.getSession(false);
        if (session == null || session.getAttribute(ADMIN_SESSION_KEY) == null) {
            log.warn("Admin access denied: {}, port: {}", uri, serverPort);
            writeErrorResponse(httpResponse, "未登录或会话已过期");
            return;
        }
        
        chain.doFilter(request, response);
    }
    
    private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.error(401, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
    
    private void writeErrorResponse(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.error(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
    
    /**
     * 判断是否是客户端 API
     */
    private boolean isClientApi(String uri) {
        // 客户端 API 路径是 /api/v1/*
        if (uri.startsWith("/api/v1/")) {
            return true;
        }
        return false;
    }
}
