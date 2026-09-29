package com.wildfirechat.pan.controller.admin;

import com.wildfirechat.pan.constant.OwnerType;
import com.wildfirechat.pan.constant.SpaceType;
import com.wildfirechat.pan.dto.Result;
import com.wildfirechat.pan.dto.vo.FileVO;
import com.wildfirechat.pan.dto.vo.SpaceVO;
import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import com.wildfirechat.pan.service.FileService;
import com.wildfirechat.pan.service.IMUserService;
import com.wildfirechat.pan.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/spaces")
public class AdminSpaceController {

    @Autowired
    private PanSpaceRepository spaceRepository;

    @Autowired
    private FileService fileService;

    @Autowired
    private IMUserService imUserService;

    @Autowired
    private PermissionService permissionService;

    /**
     * 空间列表。type 为 USER 时返回全部用户空间，为具体类型时只返回该类型
     */
    @GetMapping
    public Result<Page<SpaceVO>> list(@RequestParam(required = false) String type,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = AdminPaging.of(page, size);
        Page<PanSpace> spaces;
        if (!StringUtils.hasText(type)) {
            spaces = spaceRepository.findAll(pageable);
        } else if ("USER".equals(type)) {
            spaces = spaceRepository.findBySpaceTypeIn(List.of(SpaceType.USER_PUBLIC, SpaceType.USER_PRIVATE), pageable);
        } else {
            spaces = spaceRepository.findBySpaceTypeIn(List.of(parseSpaceType(type)), pageable);
        }
        return Result.success(spaces.map(this::toVO));
    }

    @GetMapping("/{id}")
    public Result<SpaceVO> get(@PathVariable Long id) {
        return spaceRepository.findById(id)
            .map(space -> Result.success(toVO(space)))
            .orElseGet(() -> Result.error("空间不存在"));
    }

    @GetMapping("/{id}/files")
    public Result<List<FileVO>> getSpaceFiles(@PathVariable Long id,
                                              @RequestParam(required = false) Long parentId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        if (StringUtils.hasText(keyword)) {
            return Result.success(fileService.searchBySpaceAsAdmin(id, keyword, AdminPaging.of(page, size)).getContent());
        }
        return Result.success(fileService.getFileListAsAdmin(id, parentId));
    }

    /**
     * 为用户创建空间。全局公共空间只有一个，由系统初始化，不能手动创建
     */
    @PostMapping
    public Result<SpaceVO> createSpace(@RequestParam SpaceType spaceType,
                                       @RequestParam String ownerId,
                                       @RequestParam String name,
                                       @RequestParam(required = false) Long quota) {
        if (spaceType == SpaceType.GLOBAL_PUBLIC) {
            throw new BusinessException("全局公共空间不能手动创建");
        }
        if (!StringUtils.hasText(ownerId)) {
            throw new BusinessException("用户空间必须指定所有者");
        }
        if (spaceRepository.findBySpaceTypeAndOwnerId(spaceType, ownerId).isPresent()) {
            throw new BusinessException("该空间已存在");
        }

        PanSpace space = new PanSpace();
        space.setSpaceType(spaceType);
        space.setOwnerId(ownerId);
        space.setOwnerType(OwnerType.USER);
        space.setName(name);
        if (quota != null && quota > 0) {
            space.setTotalQuota(quota);
        }
        return Result.success(toVO(spaceRepository.save(space)));
    }

    private SpaceVO toVO(PanSpace space) {
        String name = switch (space.getSpaceType()) {
            case USER_PUBLIC -> imUserService.getUserDisplayName(space.getOwnerId()) + "的公共空间";
            case USER_PRIVATE -> imUserService.getUserDisplayName(space.getOwnerId()) + "的私有空间";
            case GLOBAL_PUBLIC -> space.getName();
        };
        return SpaceVO.of(space, name, permissionService.canConsoleAccessSpace(space));
    }

    private static SpaceType parseSpaceType(String type) {
        try {
            return SpaceType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的空间类型: " + type);
        }
    }
}
