package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/files")
public class AdminFileController {
    
    @Autowired
    private FileService fileService;
    
    /**
     * 获取所有文件列表（分页）
     * 如果有 keyword 则搜索，否则返回所有文件
     */
    @GetMapping
    public Result<Page<FileVO>> list(@RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        if (keyword != null && !keyword.isEmpty()) {
            return Result.success(fileService.globalSearch(keyword, pageable));
        } else {
            return Result.success(fileService.getAllFiles(pageable));
        }
    }
    
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        // 管理员使用特殊标识删除
        fileService.deleteFile(id, "admin");
        return Result.success();
    }
    
    /**
     * 获取文件下载URL
     */
    @GetMapping("/{id}/url")
    public Result<String> getDownloadUrl(@PathVariable Long id) {
        FileVO file = fileService.getFileDetail(id, "admin");
        return Result.success(file.getStorageUrl());
    }
}
