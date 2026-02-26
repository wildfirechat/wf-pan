package com.wildfirechat.pan.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileUrlResponse {
    
    private long fileId;
    private String name;
    private String storageUrl;
}
