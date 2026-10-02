package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 只读 PDF 预览请求：网盘文件传 fileId；按链接只读打开传 url(+name)。
 */
@Data
public class PreviewPdfRequest {

    /** 网盘文件 id（与 url 二选一） */
    private Long fileId;

    /** 按链接只读打开时的文件地址（须在受信任前缀下） */
    private String url;

    /** 文件名，用于判断格式、显示标题 */
    private String name;
}
