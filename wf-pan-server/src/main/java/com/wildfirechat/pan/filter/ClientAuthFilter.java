package com.wildfirechat.pan.filter;

import com.wildfirechat.pan.service.SignService;
import com.wildfirechat.pan.service.auth.ClientAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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
 * <ul>
 *     <li>/api/v1/**：必须携带有效的 authCode，或在线文档页面的会话 Cookie（同时带 {@link #WEB_HEADER} 头）</li>
 *     <li>在线文档页面、签名下载、ONLYOFFICE 回调：放行，由各自的控制器鉴权（见 {@link FilterSupport#isClientWebPath}）</li>
 *     <li>其余路径（管理接口、管理后台页面）一律 404</li>
 * </ul>
 */
@Component
@Slf4j
public class ClientAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_KEY = "userId";
    /** 在线文档页面（H5）的会话 Cookie */
    public static final String WEB_SESSION_COOKIE = "PAN_WS";
    /** 用 Cookie 会话调接口时必须带的头（防跨站请求：跨站表单带不了自定义头） */
    public static final String WEB_HEADER = "X-Pan-Web";

    private final int adminPort;
    private final ClientAuthService clientAuthService;
    private final SignService signService;

    public ClientAuthFilter(@Value("${server.admin-port:8080}") int adminPort,
                            ClientAuthService clientAuthService,
                            SignService signService) {
        this.adminPort = adminPort;
        this.clientAuthService = clientAuthService;
        this.signService = signService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getLocalPort() == adminPort;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = FilterSupport.path(request);

        if (FilterSupport.isClientWebPath(path)) {
            chain.doFilter(request, response);
            return;
        }

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
        String userId;
        if (authCode != null && !authCode.isEmpty()) {
            userId = clientAuthService.validateAuthCode(authCode);
            if (userId == null || userId.isEmpty()) {
                FilterSupport.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 1002, "无效的 authCode");
                return;
            }
        } else {
            // 在线文档页面：用 /doc/session 换来的 Cookie 会话
            String session = webSessionCookie(request);
            if (session == null || request.getHeader(WEB_HEADER) == null) {
                FilterSupport.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 1001, "缺少 authCode");
                return;
            }
            userId = signService.verifyWebSession(session);
            if (userId == null || userId.isEmpty()) {
                FilterSupport.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 1004, "会话已过期");
                return;
            }
        }

        request.setAttribute(USER_ID_KEY, userId);
        chain.doFilter(request, response);
    }

    private static String webSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (WEB_SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
