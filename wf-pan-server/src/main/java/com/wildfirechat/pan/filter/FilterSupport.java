package com.wildfirechat.pan.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wildfirechat.pan.dto.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

final class FilterSupport {

    static final String API_PREFIX = "/api/";
    static final String CLIENT_API_PREFIX = "/api/v1/";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FilterSupport() {
    }

    /**
     * 容器解码并规范化（去掉 ".."、";参数"）后的路径。
     * 不能用 getRequestURI()：它是原始值，"/api/v1/../x" 这类路径会绕过前缀判断。
     */
    static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }

    static void writeError(HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(Result.error(code, message)));
    }
}
