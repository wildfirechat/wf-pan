package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.constant.OwnerType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.SpaceVO;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.FileService;
import com.wildfirechat.pan.service.IMUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/spaces")
public class AdminSpaceController {
    
    @Autowired
    private PanSpaceRepository spaceRepository;
    
    @Autowired
    private FileService fileService;
    
    @Autowired
    private IMUserService imUserService;
    
    @GetMapping
    public Result<Page<SpaceVO>> list(@RequestParam(required = false) String type,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        
        List<PanSpace> allSpaces = spaceRepository.findAll();
        
        // 过滤
        if (type != null && !type.isEmpty()) {
            final String filterType = type;
            allSpaces = allSpaces.stream()
                .filter(s -> {
                    String spaceType = s.getSpaceType().name();
                    if ("USER".equals(filterType)) {
                        return spaceType.startsWith("USER_");
                    } else if ("DEPT".equals(filterType)) {
                        return spaceType.startsWith("DEPT_");
                    } else {
                        return spaceType.equals(filterType);
                    }
                })
                .collect(Collectors.toList());
        }
        
        // 手动分页
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), allSpaces.size());
        List<PanSpace> pageContent = start < allSpaces.size() 
            ? allSpaces.subList(start, end) 
            : List.of();
        
        List<SpaceVO> voList = pageContent.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        
        Page<SpaceVO> result = new PageImpl<>(voList, pageable, allSpaces.size());
        return Result.success(result);
    }
    
    @GetMapping("/{id}/files")
    public Result<List<FileVO>> getSpaceFiles(@PathVariable Long id,
                                               @RequestParam(required = false) Long parentId,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        if (keyword != null && !keyword.isEmpty()) {
            Pageable pageable = PageRequest.of(page, size);
            return Result.success(fileService.searchBySpace(id, keyword, pageable, "admin").getContent());
        }
        
        return Result.success(fileService.getFileList(id, parentId, "admin"));
    }
    
    @PostMapping
    public Result<SpaceVO> createSpace(@RequestParam SpaceType spaceType,
                                        @RequestParam(required = false) String ownerId,
                                        @RequestParam String name,
                                        @RequestParam(required = false) Long quota) {
        // 检查是否已存在
        if (spaceRepository.findBySpaceTypeAndOwnerId(spaceType, ownerId).isPresent()) {
            return Result.error("该空间已存在");
        }
        
        PanSpace space = new PanSpace();
        space.setSpaceType(spaceType);
        space.setOwnerId(ownerId);
        space.setOwnerType(getOwnerType(spaceType));
        space.setName(name);
        if (quota != null) {
            space.setTotalQuota(quota);
        }
        
        PanSpace saved = spaceRepository.save(space);
        return Result.success(convertToVO(saved));
    }
    
    private OwnerType getOwnerType(SpaceType spaceType) {
        switch (spaceType) {
            case GLOBAL_PUBLIC:
                return OwnerType.SYSTEM;
            case DEPT_PUBLIC:
            case DEPT_PRIVATE:
                return OwnerType.DEPT;
            case USER_PUBLIC:
            case USER_PRIVATE:
                return OwnerType.USER;
            default:
                return OwnerType.SYSTEM;
        }
    }
    
    private SpaceVO convertToVO(PanSpace space) {
        String displayName = space.getName() != null ? space.getName() : "";
        
        // 对于用户空间，显示为 "xxx的公共空间/私有空间"
        if ((space.getSpaceType() == SpaceType.USER_PUBLIC || space.getSpaceType() == SpaceType.USER_PRIVATE) 
                && space.getOwnerId() != null && !space.getOwnerId().isEmpty()) {
            String userName = imUserService.getUserDisplayName(space.getOwnerId());
            if (space.getSpaceType() == SpaceType.USER_PUBLIC) {
                displayName = userName + "的公共空间";
            } else {
                displayName = userName + "的私有空间";
            }
        }
        
        return SpaceVO.builder()
            .id(space.getId() != null ? space.getId() : 0L)
            .spaceType(space.getSpaceType())
            .ownerId(space.getOwnerId() != null ? space.getOwnerId() : "")
            .name(displayName)
            .totalQuota(space.getTotalQuota() != null ? space.getTotalQuota() : 0L)
            .usedQuota(space.getUsedQuota() != null ? space.getUsedQuota() : 0L)
            .fileCount(space.getFileCount() != null ? space.getFileCount() : 0)
            .folderCount(space.getFolderCount() != null ? space.getFolderCount() : 0)
            .autoInit(space.getAutoInit() != null ? space.getAutoInit() : false)
            .createdAt(space.getCreatedAt())
            .updatedAt(space.getUpdatedAt())
            .canManage(true)
            .build();
    }
}
