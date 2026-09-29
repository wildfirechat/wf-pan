package com.wildfirechat.pan.service;

import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空间配额与计数。全部使用原子 UPDATE，避免并发时读-改-写丢失更新。
 */
@Service
@RequiredArgsConstructor
public class SpaceQuotaService {

    private final PanSpaceRepository spaceRepository;

    /**
     * 占用配额，超出总配额时抛出异常
     */
    @Transactional
    public void reserve(Long spaceId, long size, String errorMessage) {
        if (size > 0 && spaceRepository.tryIncreaseUsedQuota(spaceId, size) == 0) {
            throw new BusinessException(errorMessage);
        }
    }

    @Transactional
    public void release(Long spaceId, long size) {
        if (size > 0) {
            spaceRepository.decreaseUsedQuota(spaceId, size);
        }
    }

    @Transactional
    public void adjustCounts(Long spaceId, int files, int folders) {
        if (files != 0 || folders != 0) {
            spaceRepository.adjustCounts(spaceId, files, folders);
        }
    }
}
