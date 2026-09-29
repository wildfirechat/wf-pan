package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.filter.AdminAuthFilter;
import com.wildfirechat.pan.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
public class AdminFileController {

    @Autowired
    private FileService fileService;

    /**
     * 文件列表（分页），有 keyword 时搜索。是否包含用户私有空间由 pan.admin.manage-private-space 决定
     */
    @GetMapping
    public Result<Page<FileVO>> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = AdminPaging.of(page, size);
        if (keyword != null && !keyword.isEmpty()) {
            return Result.success(fileService.globalSearchAsAdmin(keyword, pageable));
        }
        return Result.success(fileService.getAllFilesAsAdmin(pageable));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @SessionAttribute(AdminAuthFilter.ADMIN_SESSION_KEY) String adminUser) {
        fileService.deleteFileAsAdmin(id, adminUser);
        return Result.success();
    }

    @GetMapping("/{id}/url")
    public Result<String> getDownloadUrl(@PathVariable Long id) {
        return Result.success(fileService.getFileDetailAsAdmin(id).getStorageUrl());
    }
}
