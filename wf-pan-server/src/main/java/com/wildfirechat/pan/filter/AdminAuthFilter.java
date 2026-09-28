package com.wildfirechat.pan.filter;

import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理端口的访问控制：
 * <ul>
 *     <li>禁止访问客户端接口 /api/v1/** 和客户端网页路径（在线文档、下载、ONLYOFFICE 回调）</li>
 *     <li>除登录接口外，/api/** 需要有效的管理员会话，且该管理员仍在全局管理员列表中</li>
 *     <li>其余路径（管理后台前端页面和静态资源）放行</li>
 * </ul>
 */
@Component
@Slf4j
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ADMIN_SESSION_KEY = "admin_user";

    private static final String LOGIN_PATH = "/api/auth/login";

    private final int adminPort;
    private final PanGlobalAdminRepository globalAdminRepository;

    public AdminAuthFilter(@Value("${server.admin-port:8080}") int adminPort,
                           PanGlobalAdminRepository globalAdminRepository) {
        this.adminPort = adminPort;
        this.globalAdminRepository = globalAdminRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getLocalPort() != adminPort;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = FilterSupport.path(request);

        if (path.startsWith(FilterSupport.CLIENT_API_PREFIX)) {
            log.warn("Client API requested on admin port: {}", path);
            FilterSupport.writeError(response, HttpServletResponse.SC_FORBIDDEN, 403, "客户端接口不允许从管理端口访问");
            return;
        }
        if (FilterSupport.isClientWebPath(path)) {
            FilterSupport.writeError(response, HttpServletResponse.SC_NOT_FOUND, 404, "Not Found");
            return;
        }

        if (!path.startsWith(FilterSupport.API_PREFIX) || path.equals(LOGIN_PATH)) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        String adminUser = session == null ? null : (String) session.getAttribute(ADMIN_SESSION_KEY);
        if (adminUser == null) {
            FilterSupport.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "未登录或会话已过期");
            return;
        }

        // 管理员被移除后立即失效，而不是等会话过期
        if (!globalAdminRepository.existsByUserId(adminUser)) {
            log.warn("Session of removed admin rejected: {}", adminUser);
            session.invalidate();
            FilterSupport.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "管理员账号已失效");
            return;
        }

        chain.doFilter(request, response);
    }
}
