package com.wildfirechat.pan.service.storage;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存储 URL 的解析与规范化。
 * <p>
 * 规范形式为 {@code host[:port]/解码后的路径}：忽略 http/https 差异、默认端口、查询参数，
 * 用于判断 URL 是否位于某个前缀（bucket）之下。含 ".." 等可能改变路径含义的 URL 一律拒绝。
 */
public final class StorageUrls {

    private static final Pattern HTTP_URL =
        Pattern.compile("^(https?)://([^/?#]+)([^?#]*)(?:\\?[^#]*)?(?:#.*)?$", Pattern.CASE_INSENSITIVE);

    private StorageUrls() {
    }

    /**
     * @param host 小写的 host[:port]，已去掉默认端口
     * @param path 以 "/" 开头的已解码路径
     */
    public record ParsedUrl(String host, String path) {

        public String canonical() {
            return host + path;
        }

        public boolean isUnder(String canonicalPrefix) {
            return canonical().startsWith(canonicalPrefix);
        }

        /** 路径最后一段 */
        public String fileName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    public static Optional<ParsedUrl> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher matcher = HTTP_URL.matcher(url);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String scheme = matcher.group(1).toLowerCase(Locale.ROOT);
        String authority = matcher.group(2).toLowerCase(Locale.ROOT);
        if (authority.contains("@") || authority.contains("\\")) {
            return Optional.empty();
        }
        String path;
        try {
            // 路径中的 "+" 是字面量，不是空格
            path = URLDecoder.decode(matcher.group(3).replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (hasDotSegment(path) || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        return Optional.of(new ParsedUrl(stripDefaultPort(scheme, authority), path));
    }

    /**
     * 把配置的 URL 前缀转成规范形式，保证以 "/" 结尾（避免 /media 匹配到 /media-private）
     */
    public static Optional<String> normalizePrefix(String prefix) {
        return parse(prefix).map(parsed -> {
            String canonical = parsed.canonical();
            return canonical.endsWith("/") ? canonical : canonical + "/";
        });
    }

    /**
     * 路径风格 host/bucket/key
     */
    public static Optional<ObjectLocation> pathStyle(ParsedUrl url) {
        String path = url.path().substring(1);
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new ObjectLocation(path.substring(0, slash), path.substring(slash + 1)));
    }

    /**
     * 虚拟主机风格 bucket.endpoint/key
     */
    public static Optional<ObjectLocation> virtualHostStyle(ParsedUrl url) {
        String host = url.host();
        int dot = host.indexOf('.');
        String key = url.path().substring(1);
        if (dot <= 0 || key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ObjectLocation(host.substring(0, dot), key));
    }

    /**
     * 对象 key 编码为 URL 路径（保留 "/"）
     */
    public static String encodePath(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%2F", "/");
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String stripDefaultPort(String scheme, String authority) {
        if (scheme.equals("http") && authority.endsWith(":80")) {
            return authority.substring(0, authority.length() - 3);
        }
        if (scheme.equals("https") && authority.endsWith(":443")) {
            return authority.substring(0, authority.length() - 4);
        }
        return authority;
    }
}
