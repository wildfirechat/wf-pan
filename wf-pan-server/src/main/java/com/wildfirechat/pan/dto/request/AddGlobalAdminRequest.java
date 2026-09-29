package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddGlobalAdminRequest {

    @NotBlank(message = "用户ID不能为空")
    @Size(max = 64, message = "用户ID不能超过64个字符")
    private String userId;

    @Size(max = 128, message = "用户名不能超过128个字符")
    private String username;

    @NotBlank(message = "登录密码不能为空")
    @Size(min = 8, max = 64, message = "登录密码长度需为8-64位")
    private String password;
}
