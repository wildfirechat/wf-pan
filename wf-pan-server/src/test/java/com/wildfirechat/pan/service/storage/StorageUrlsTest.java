package com.wildfirechat.pan.service.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorageUrlsTest {

    @Test
    void normalizesSchemeHostPortAndEncoding() {
        StorageUrls.ParsedUrl a = StorageUrls.parse("http://MinIO.example.com:80/wf-pan/a%20b.txt?x=1").orElseThrow();
        StorageUrls.ParsedUrl b = StorageUrls.parse("https://minio.example.com/wf-pan/a b.txt").orElseThrow();

        assertThat(a.canonical()).isEqualTo("minio.example.com/wf-pan/a b.txt");
        assertThat(b.canonical()).isEqualTo(a.canonical());
        assertThat(a.fileName()).isEqualTo("a b.txt");
    }

    @Test
    void rejectsNonHttpAndAmbiguousUrls() {
        assertThat(StorageUrls.parse("javascript:alert(1)")).isEmpty();
        assertThat(StorageUrls.parse("data:text/html,x")).isEmpty();
        assertThat(StorageUrls.parse("ftp://host/bucket/key")).isEmpty();
        assertThat(StorageUrls.parse("//host/bucket/key")).isEmpty();
        assertThat(StorageUrls.parse("http://user@host/bucket/key")).isEmpty();
        assertThat(StorageUrls.parse("http://host/media/../secret/key")).isEmpty();
        assertThat(StorageUrls.parse("http://host/media/%2e%2e/secret/key")).isEmpty();
        assertThat(StorageUrls.parse("http://host/media/..%2Fsecret/key")).isEmpty();
        assertThat(StorageUrls.parse("http://host/media/a%0Ab")).isEmpty();
        assertThat(StorageUrls.parse("http://host/media/%zz")).isEmpty();
    }

    @Test
    void prefixAlwaysEndsWithSlash() {
        String prefix = StorageUrls.normalizePrefix("http://host:9000/media").orElseThrow();

        assertThat(prefix).isEqualTo("host:9000/media/");
        assertThat(StorageUrls.parse("http://host:9000/media/key").orElseThrow().isUnder(prefix)).isTrue();
        assertThat(StorageUrls.parse("http://host:9000/media-private/key").orElseThrow().isUnder(prefix)).isFalse();
    }

    @Test
    void locatesObjectsInBothAddressingStyles() {
        StorageUrls.ParsedUrl pathStyle = StorageUrls.parse("http://host:9000/media/dir/file.txt").orElseThrow();
        StorageUrls.ParsedUrl virtualHost = StorageUrls.parse("https://media.oss-cn-hangzhou.aliyuncs.com/dir/file.txt").orElseThrow();

        assertThat(StorageUrls.pathStyle(pathStyle)).contains(new ObjectLocation("media", "dir/file.txt"));
        assertThat(StorageUrls.virtualHostStyle(virtualHost)).contains(new ObjectLocation("media", "dir/file.txt"));
        assertThat(StorageUrls.pathStyle(StorageUrls.parse("http://host/media").orElseThrow())).isEmpty();
    }

    @Test
    void encodedPathDecodesBackToKey() {
        String key = "abc-中文 名称+1.txt";
        String url = "http://host/bucket/" + StorageUrls.encodePath(key);

        assertThat(StorageUrls.parse(url).orElseThrow().path()).isEqualTo("/bucket/" + key);
    }
}
