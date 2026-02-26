package com.wildfirechat.pan.dto.vo;

import lombok.Data;

@Data
public class UserInfoVO {
    private String userId;
    private String name;
    private String displayName;
    private String portrait;
    private String mobile;
    private String email;
    private String address;
    private String company;
    private String extra;
}
