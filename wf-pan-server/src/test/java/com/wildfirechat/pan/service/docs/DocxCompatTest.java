package com.wildfirechat.pan.service.docs;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocxCompatTest {

    /** WPS 把 PDF 转成 Word 时写的样子 */
    private static final String INLINE = "<w:framePr w:wrap=\"auto\" w:vAnchor=\"margin\" w:hAnchor=\"text\" w:yAlign=\"inline\"/>";
    private static final String POSITIONED = "<w:framePr w:w=\"2000\" w:wrap=\"around\" w:vAnchor=\"text\" w:hAnchor=\"page\" w:x=\"1200\" w:yAlign=\"inline\"/>";
    private static final String DROP_CAP = "<w:framePr w:dropCap=\"drop\" w:lines=\"3\" w:wrap=\"around\" w:vAnchor=\"text\" w:hAnchor=\"text\"/>";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};

    @Test
    void dropsLayoutFreeInlineFramesEverywhere() throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", bytes("<Types/>"));
        parts.put("word/", new byte[0]);
        parts.put("word/styles.xml", bytes("<w:style><w:pPr><w:keepNext w:val=\"0\"/>" + INLINE + "</w:pPr></w:style>"));
        parts.put("word/document.xml", bytes("<w:body>"
            + "<w:p><w:pPr>" + INLINE + "<w:jc w:val=\"center\"/></w:pPr><w:r><w:t>一</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:framePr w:yAlign=\"inline\" w:wrap=\"auto\"></w:framePr></w:pPr></w:p>"
            + "<w:p><w:pPr>" + POSITIONED + "</w:pPr></w:p>"
            + "<w:p><w:pPr>" + DROP_CAP + "</w:pPr></w:p>"
            + "</w:body>"));
        parts.put("word/header1.xml", bytes("<w:hdr><w:p><w:pPr>" + INLINE + "</w:pPr></w:p></w:hdr>"));
        parts.put("word/media/image1.png", PNG);

        Map<String, byte[]> out = unzip(transfer(zip(parts)));

        assertThat(out.keySet()).containsExactlyElementsOf(parts.keySet());
        assertThat(text(out, "word/styles.xml")).isEqualTo("<w:style><w:pPr><w:keepNext w:val=\"0\"/></w:pPr></w:style>");
        assertThat(text(out, "word/header1.xml")).doesNotContain("framePr");
        assertThat(text(out, "word/document.xml"))
            .isEqualTo("<w:body>"
                + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>一</w:t></w:r></w:p>"
                + "<w:p><w:pPr></w:pPr></w:p>"
                + "<w:p><w:pPr>" + POSITIONED + "</w:pPr></w:p>"
                + "<w:p><w:pPr>" + DROP_CAP + "</w:pPr></w:p>"
                + "</w:body>");
        assertThat(out.get("word/media/image1.png")).isEqualTo(PNG);
        assertThat(out.get("[Content_Types].xml")).isEqualTo(parts.get("[Content_Types].xml"));
    }

    @Test
    void passesThroughWhenNothingToFix() throws IOException {
        Map<String, byte[]> docx = new LinkedHashMap<>();
        docx.put("word/document.xml", bytes("<w:body><w:p><w:pPr>" + POSITIONED + "</w:pPr></w:p></w:body>"));
        byte[] src = zip(docx);
        assertThat(transfer(src)).isEqualTo(src);

        // 不是 docx（没有 word/document.xml）的 zip 不动
        Map<String, byte[]> xlsx = new LinkedHashMap<>();
        xlsx.put("xl/workbook.xml", bytes("<workbook/>"));
        xlsx.put("word/styles.xml", bytes(INLINE));
        src = zip(xlsx);
        assertThat(transfer(src)).isEqualTo(src);

        src = bytes("%PDF-1.7 不是 zip");
        assertThat(transfer(src)).isEqualTo(src);

        src = bytes("PK\u0003\u0004 坏的 zip");
        assertThat(transfer(src)).isEqualTo(src);

        assertThat(transfer(new byte[0])).isEmpty();
    }

    private static byte[] transfer(byte[] src) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DocxCompat.transfer(new ByteArrayInputStream(src), out);
        return out.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Map<String, byte[]> parts, String name) {
        return new String(parts.get(name), StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                parts.put(e.getName(), zis.readAllBytes());
            }
        }
        return parts;
    }
}
