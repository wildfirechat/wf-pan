package com.wildfirechat.pan.repository;

import com.wildfirechat.pan.entity.PanFileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PanFileVersionRepository extends JpaRepository<PanFileVersion, Long> {

    List<PanFileVersion> findByFileIdOrderByVersionNoDesc(Long fileId);

    Optional<PanFileVersion> findByFileIdAndVersionNo(Long fileId, Integer versionNo);

    boolean existsByFileIdAndVersionNo(Long fileId, Integer versionNo);

    long countByStorageKey(String storageKey);

    List<PanFileVersion> findTop500ByStorageKeyIsNull();
}
