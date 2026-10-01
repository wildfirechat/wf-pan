package com.wildfirechat.pan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 按链接只读打开在线文档：文件不在网盘里（没有 fileId），只给一个存储地址。
 *
 * 地址必须是受信任前缀（{@code media.trusted_url_prefixes}）下的，否则拒绝。
 */
@Data
public class ViewUrlRequest {

    @NotBlank(message = "文件地址不能为空")
    @Size(max = 1024, message = "文件地址过长")
    private String url;

    /** 文件名：用来判断格式与显示标题（地址里带不出扩展名时必传） */
    @Size(max = 255, message = "文件名过长")
    private String name;

    /** pc / mobile。手机端同样只读 */
    private String platform = "pc";
}
