package com.wildfirechat.pan.dto.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardStatsVO {
    
    private long totalUsers;
    private long totalFiles;
    private long totalFolders;
    private long totalStorageUsed;
    private long todayUploads;
}
