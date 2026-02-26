package com.wildfirechat.pan.service;

import com.wildfirechat.pan.entity.PanSpace;
import com.wildfirechat.pan.repository.PanSpaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpaceQuotaService {
    
    @Autowired
    private PanSpaceRepository spaceRepository;
    
    @Transactional
    public void increaseUsedQuota(Long spaceId, Long size) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space != null) {
            space.setUsedQuota(space.getUsedQuota() + size);
            spaceRepository.save(space);
        }
    }
    
    @Transactional
    public void decreaseUsedQuota(Long spaceId, Long size) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space != null) {
            long newQuota = space.getUsedQuota() - size;
            space.setUsedQuota(Math.max(0, newQuota));
            spaceRepository.save(space);
        }
    }
    
    @Transactional
    public void incrementFileCount(Long spaceId) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space != null) {
            space.setFileCount(space.getFileCount() + 1);
            spaceRepository.save(space);
        }
    }
    
    @Transactional
    public void decrementFileCount(Long spaceId) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space != null) {
            space.setFileCount(Math.max(0, space.getFileCount() - 1));
            spaceRepository.save(space);
        }
    }
    
    @Transactional
    public void incrementFolderCount(Long spaceId) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space != null) {
            space.setFolderCount(space.getFolderCount() + 1);
            spaceRepository.save(space);
        }
    }
    
    @Transactional
    public void decrementFolderCount(Long spaceId) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space != null) {
            space.setFolderCount(Math.max(0, space.getFolderCount() - 1));
            spaceRepository.save(space);
        }
    }
    
    public boolean checkQuota(Long spaceId, Long additionalSize) {
        PanSpace space = spaceRepository.findById(spaceId).orElse(null);
        if (space == null) return false;
        
        return (space.getUsedQuota() + additionalSize) <= space.getTotalQuota();
    }
}
