package com.wildfirechat.pan.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileUrlResponse {
    
    private long fileId;
    private String name;
    /** 下载地址（字段名沿用旧版；网盘桶私有时是本服务签名的短时链接） */
    private String storageUrl;
    private int versionNo;
    /** VIEW / EDIT */
    private String permission;
}
