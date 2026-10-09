package com.wildfirechat.pan.service.docs;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 文件交给 ONLYOFFICE（打开、转 PDF）之前，修正 docx 里它排不对的写法；不需要修正的文件原样转发。
 *
 * 目前只有一处：「空」文本框架 {@code <w:framePr w:yAlign="inline" …/>}（没有宽高、没有位置，纵向随正文）。
 * WPS 把 PDF 转成 Word 时会把它写进「正文」样式和几乎每个段落，WPS 自己照常按正文排版；
 * ONLYOFFICE 却把框架属性相同的相邻段落合成一个文本框，文本框不能跨页 ⇒ 只排出第一页，后面的内容全被截掉
 * （实测一份 WPS 里 6 页的合同只出 1 页；去掉这些属性后正常分页）。
 * 这种框架不定位、不定尺寸，去掉后版面与「按正文排」一致；带宽高、位置、首字下沉的框架不动。
 */
@Slf4j
public final class DocxCompat {

    private DocxCompat() {
    }

    private static final Pattern FRAME_PR = Pattern.compile("<w:framePr\\b([^>]*?)\\s*(?:/>|>\\s*</w:framePr>)");
    private static final Pattern ATTR = Pattern.compile("([\\w:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    /** 只有这几个属性、且纵向是 inline 的框架才去掉：它们不决定框架的大小和位置 */
    private static final Set<String> NO_LAYOUT_ATTRS = Set.of("w:wrap", "w:vAnchor", "w:hAnchor", "w:yAlign");

    /** 把 in 的内容写到 out：是需要修正的 docx 就写修正后的，否则原样写 */
    public static void transfer(InputStream in, OutputStream out) throws IOException {
        BufferedInputStream bin = new BufferedInputStream(in);
        bin.mark(4);
        byte[] head = bin.readNBytes(4);
        bin.reset();
        // 不是 zip（doc、pdf、txt…）直接转发
        if (head.length < 4 || head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) {
            bin.transferTo(out);
            return;
        }
        Path tmp = Files.createTempFile("wf-docs-src-", ".zip");
        try {
            Files.copy(bin, tmp, StandardCopyOption.REPLACE_EXISTING);
            ZipFile zip = null;
            Map<String, byte[]> fixed = Map.of();
            try {
                zip = new ZipFile(tmp.toFile());
                fixed = fixParts(zip);
            } catch (IOException | RuntimeException e) {
                // 读不了的 zip 交给 ONLYOFFICE 自己判断，跟以前一样
                log.debug("docx 修正跳过：{}", e.getMessage());
            }
            try {
                if (!fixed.isEmpty()) {
                    write(zip, fixed, out);
                    return;
                }
            } finally {
                if (zip != null) {
                    zip.close();
                }
            }
            Files.copy(tmp, out);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 需要改的部件 → 改后的内容；不是 docx 或没有要改的返回空 */
    static Map<String, byte[]> fixParts(ZipFile zip) throws IOException {
        Map<String, byte[]> fixed = new LinkedHashMap<>();
        if (zip.getEntry("word/document.xml") == null) {
            return fixed;
        }
        int removed = 0;
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory() || !name.startsWith("word/") || !name.endsWith(".xml")) {
                continue;
            }
            String xml;
            try (InputStream in = zip.getInputStream(e)) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!xml.contains("<w:framePr")) {
                continue;
            }
            Matcher m = FRAME_PR.matcher(xml);
            StringBuilder sb = new StringBuilder(xml.length());
            int n = 0;
            while (m.find()) {
                boolean drop = isLayoutFreeInlineFrame(m.group(1));
                m.appendReplacement(sb, drop ? "" : Matcher.quoteReplacement(m.group()));
                if (drop) {
                    n++;
                }
            }
            if (n > 0) {
                m.appendTail(sb);
                fixed.put(name, sb.toString().getBytes(StandardCharsets.UTF_8));
                removed += n;
            }
        }
        if (removed > 0) {
            log.info("docx 去掉 {} 处纵向 inline 的空文本框架（{}），否则 ONLYOFFICE 只排得出第一页", removed, fixed.keySet());
        }
        return fixed;
    }

    static boolean isLayoutFreeInlineFrame(String attrs) {
        Matcher a = ATTR.matcher(attrs);
        boolean inline = false;
        while (a.find()) {
            String name = a.group(1);
            String value = a.group(2) != null ? a.group(2) : a.group(3);
            if (!NO_LAYOUT_ATTRS.contains(name)) {
                return false;
            }
            if ("w:yAlign".equals(name)) {
                inline = "inline".equals(value);
            }
        }
        return inline;
    }

    /** 按原来的顺序重新打包，改过的部件换成新内容 */
    private static void write(ZipFile zip, Map<String, byte[]> fixed, OutputStream out) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        Set<String> written = new HashSet<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            // 重名条目 ZipOutputStream 写不了；ZipFile 读的也只是第一个
            if (!written.add(e.getName())) {
                continue;
            }
            zos.putNextEntry(new ZipEntry(e.getName()));
            byte[] data = fixed.get(e.getName());
            if (data != null) {
                zos.write(data);
            } else if (!e.isDirectory()) {
                try (InputStream in = zip.getInputStream(e)) {
                    in.transferTo(zos);
                }
            }
            zos.closeEntry();
        }
        zos.finish();
    }
}
