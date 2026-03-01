package com.wildfirechat.pan.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.service.auth.ClientAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class ClientAuthFilter implements Filter {

    public static final String USER_ID_KEY = "userId";
    public static final String USER_NAME_KEY = "userName";

    @Value("${server.port}")
    private int port;

    @Value("${server.admin-port}")
    private int adminPort;

    @Autowired
    private ClientAuthService clientAuthService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // 获取请求端口
        int serverPort = request.getLocalPort();
        String uri = httpRequest.getRequestURI();
        
        // 严格端口隔离：客户端端口禁止访问管理接口
        if (serverPort == port) {
            if (isAdminApi(uri)) {
                log.warn("Client port {} attempted to access admin API: {}", serverPort, uri);
                writeErrorResponse(httpResponse, 403, "管理接口不允许从客户端端口访问");
                return;
            }
        }
        
        log.debug("Request port {}, client port {}", serverPort, port);
        
        // 只允许客户端端口的请求进入客户端接口
        if (serverPort != port) {
            chain.doFilter(request, response);
            return;
        }
        
        log.debug("Client request: {}, port: {}", uri, serverPort);
        
        // 从 header 获取 authCode（参考 wf-poll-server 的 AuthFilter）
        String authCode = httpRequest.getHeader("authCode");
        if (authCode == null || authCode.isEmpty()) {
            log.warn("Missing authCode in request: {}", uri);
            writeErrorResponse(httpResponse, 1001, "缺少 authCode");
            return;
        }
        
        // 验证 authCode
        String userId = clientAuthService.validateAuthCode(authCode);
        if (userId == null) {
            log.warn("Invalid authCode: {}", authCode);
            writeErrorResponse(httpResponse, 1002, "无效的 authCode");
            return;
        }
        
        // 将 userId 设置到 request attribute 中
        httpRequest.setAttribute(USER_ID_KEY, userId);
        // userName 暂时用 userId，后续可以从IM服务获取
        httpRequest.setAttribute(USER_NAME_KEY, userId);
        
        log.debug("Client auth success, userId: {}", userId);
        
        chain.doFilter(request, response);
    }
    
    private void writeErrorResponse(HttpServletResponse response, int code, String message) throws IOException {
        // 403 使用 FORBIDDEN，其他使用 UNAUTHORIZED
        if (code == 403) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.error(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
    
    /**
     * 判断是否是管理接口
     */
    private boolean isAdminApi(String uri) {
        // 管理API是 /api/* 但不是 /api/v1/*
        if (uri.startsWith("/api/") && !uri.startsWith("/api/v1/")) {
            return true;
        }
        return false;
    }
}
