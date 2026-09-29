package com.wildfirechat.pan.service;

import com.wildfirechat.pan.config.OssConfig;
import com.wildfirechat.pan.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageServiceTest {

    private static StorageService minio(String trustedPrefixes) {
        OssConfig config = new OssConfig();
        config.setMediaType(OssConfig.TYPE_WILDFIRE);
        config.setServerUrl("http://minio.local:9000");
        config.setAccessKey("ak");
        config.setSecretKey("sk");
        config.setBucket("wf-pan");
        config.setTrustedUrlPrefixes(trustedPrefixes);
        return new StorageService(config);
    }

    @Test
    void referencesInPanBucketAreOwned() {
        StorageService storage = minio("");

        StorageService.StoredObject object = storage.resolveReference("http://minio.local:9000/wf-pan/dir/a.txt");

        assertThat(object.url()).isEqualTo("http://minio.local:9000/wf-pan/dir/a.txt");
        assertThat(object.key()).isEqualTo("dir/a.txt");
    }

    @Test
    void differentSpellingsOfSameObjectHaveSameKey() {
        StorageService storage = minio("");

        assertThat(storage.ownedKey("https://MINIO.local:9000/wf-pan/a%20b.txt?v=1")).contains("a b.txt");
        assertThat(storage.ownedKey("http://minio.local:9000/wf-pan/a b.txt")).contains("a b.txt");
    }

    @Test
    void objectsOutsidePanBucketAreNeverOwned() {
        StorageService storage = minio("");

        assertThat(storage.ownedKey("http://minio.local:9000/other-bucket/a.txt")).isEmpty();
        assertThat(storage.ownedKey("http://evil.example.com/wf-pan/a.txt")).isEmpty();
        assertThat(storage.ownedKey("http://minio.local:9000/wf-pan/../other/a.txt")).isEmpty();
        assertThat(storage.ownedKey("http://minio.local:9000/wf-pan-private/a.txt")).isEmpty();
    }

    @Test
    void untrustedReferencesAreRejected() {
        StorageService storage = minio("http://minio.local:9000/media/");

        assertThat(storage.resolveReference("http://minio.local:9000/media/a.txt").key()).isEmpty();
        assertThatThrownBy(() -> storage.resolveReference("http://minio.local:9000/secret/a.txt"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.resolveReference("https://phishing.example.com/a.txt"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.resolveReference("javascript:alert(document.cookie)"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void copyingFromUntrustedBucketIsRejectedBeforeAnyStorageCall() {
        StorageService storage = minio("http://minio.local:9000/media/");

        assertThatThrownBy(() -> storage.importObject("http://minio.local:9000/secret/a.txt"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("不允许从该地址复制文件");
    }

    @Test
    void importingOwnedObjectDoesNotCopy() {
        StorageService storage = minio("");

        StorageService.StoredObject object = storage.importObject("http://minio.local:9000/wf-pan/a.txt");

        assertThat(object.key()).isEqualTo("a.txt");
    }

    @Test
    void withoutStorageConfigOnlyHttpUrlsAreAccepted() {
        StorageService storage = new StorageService(new OssConfig());

        assertThat(storage.resolveReference("https://files.example.com/a.txt").key()).isEmpty();
        assertThatThrownBy(() -> storage.resolveReference("javascript:alert(1)")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.importObject("https://files.example.com/a.txt")).isInstanceOf(BusinessException.class);
    }
}
