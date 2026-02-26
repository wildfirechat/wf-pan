package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.vo.DashboardStatsVO;
import com.wildfirechat.pan.repository.PanFileRepository;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/dashboard")
public class AdminDashboardController {
    
    @Autowired
    private PanSpaceRepository spaceRepository;
    
    @Autowired
    private PanFileRepository fileRepository;
    
    @GetMapping("/stats")
    public Result<DashboardStatsVO> getStats() {
        // 统计实际用户数（从文件创建者中统计唯一用户）
        long totalUsers = fileRepository.countDistinctCreators();
        
        // 统计文件数（不包括文件夹）
        long totalFiles = fileRepository.countFilesOnly();
        
        // 统计文件夹数
        long totalFolders = fileRepository.countFoldersOnly();
        
        // 统计存储用量（所有文件大小总和）
        long totalStorageUsed = fileRepository.sumFileSize();
        
        // 统计今日上传数
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        long todayUploads = fileRepository.countTodayUploads(today);
        
        DashboardStatsVO stats = DashboardStatsVO.builder()
            .totalUsers(totalUsers)
            .totalFiles(totalFiles)
            .totalFolders(totalFolders)
            .totalStorageUsed(totalStorageUsed)
            .todayUploads(todayUploads)
            .build();
        
        return Result.success(stats);
    }
}
