package com.wildfirechat.pan.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理后台视图控制器
 * 处理前端路由，支持SPA
 */
@Controller
public class AdminViewController {
    
    /**
     * 管理后台首页 - 重定向到 index.html
     */
    @GetMapping("/admin")
    public String adminRoot() {
        return "redirect:/admin/index.html";
    }
    
    /**
     * 处理前端路由 - 所有非文件请求都返回index.html
     */
    @GetMapping("/admin/{path:[^\\.]*}")
    public String adminRoutes() {
        return "forward:/admin/index.html";
    }
}
