package com.wildfirechat.pan.filter;

import com.wildfirechat.pan.service.auth.ClientAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 客户端端口（管理端口以外的所有端口）的访问控制：
 * 只开放 /api/v1/**，且必须携带有效的 authCode。
 */
@Component
@Slf4j
public class ClientAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_KEY = "userId";

    private final int adminPort;
    private final ClientAuthService clientAuthService;

    public ClientAuthFilter(@Value("${server.admin-port:8080}") int adminPort,
                            ClientAuthService clientAuthService) {
        this.adminPort = adminPort;
        this.clientAuthService = clientAuthService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getLocalPort() == adminPort;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = FilterSupport.path(request);

        // 管理接口和管理后台页面都不对客户端端口开放
        if (!path.startsWith(FilterSupport.CLIENT_API_PREFIX)) {
            FilterSupport.writeError(response, HttpServletResponse.SC_NOT_FOUND, 404, "Not Found");
            return;
        }

        // CORS 预检请求不携带自定义 header，交给 Spring MVC 的 CORS 处理
        if (CorsUtils.isPreFlightRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        String authCode = request.getHeader("authCode");
        if (authCode == null || authCode.isEmpty()) {
            FilterSupport.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 1001, "缺少 authCode");
            return;
        }

        String userId = clientAuthService.validateAuthCode(authCode);
        if (userId == null || userId.isEmpty()) {
            FilterSupport.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 1002, "无效的 authCode");
            return;
        }

        request.setAttribute(USER_ID_KEY, userId);
        chain.doFilter(request, response);
    }
}
