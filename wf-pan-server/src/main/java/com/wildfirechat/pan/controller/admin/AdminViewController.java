package com.wildfirechat.pan.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理后台是单页应用，前端路由（/dashboard、/files 等）刷新时返回 index.html。
 * 客户端端口不开放这些路径（见 ClientAuthFilter）。
 */
@Controller
public class AdminViewController {

    @GetMapping("/{path:[^.]*}")
    public String spaRoute() {
        return "forward:/index.html";
    }
}
