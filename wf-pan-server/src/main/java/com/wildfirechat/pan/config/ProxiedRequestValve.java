package com.wildfirechat.pan.config;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;

/**
 * 标记经代理（NG）转来的请求。必须排在 RemoteIpValve 之前：
 * server.forward-headers-strategy=native 时 RemoteIpValve 处理完 X-Forwarded-For 会把它删掉，
 * 之后就分不清请求是直连还是经代理转来的。
 */
public class ProxiedRequestValve extends ValveBase {

    /** 请求属性：存在即表示经过代理 */
    public static final String PROXIED_ATTRIBUTE = ProxiedRequestValve.class.getName() + ".PROXIED";

    public ProxiedRequestValve() {
        super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Real-IP") != null
                || request.getHeader("Forwarded") != null) {
            request.setAttribute(PROXIED_ATTRIBUTE, Boolean.TRUE);
        }
        getNext().invoke(request, response);
    }
}
