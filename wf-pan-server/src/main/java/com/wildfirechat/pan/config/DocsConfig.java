package com.wildfirechat.pan.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 在线文档（ONLYOFFICE Docs）对接与网盘对外地址配置
 */
@Configuration
@Data
public class DocsConfig {

    /** 是否启用在线文档（关闭时只保留网盘本身的功能） */
    @Value("${docs.enabled:false}")
    private boolean enabled;

    /** 与 ONLYOFFICE 共用的 JWT 密钥（HS256），安装时生成 */
    @Value("${docs.jwt_secret:}")
    private String jwtSecret;

    /** 本服务调 ONLYOFFICE 的内网地址（转换接口、取保存结果） */
    @Value("${docs.server_internal_url:http://wf-docs}")
    private String serverInternalUrl;

    /** 浏览器加载编辑器用的路径（与网盘页面同源，由 NG 反代到 ONLYOFFICE） */
    @Value("${docs.server_public_path:/docs}")
    private String serverPublicPath;

    /** ONLYOFFICE 回连本服务的内网地址（取文件、保存回调），只在容器网络内可达 */
    @Value("${docs.callback_base_url:http://wf-pan:8081}")
    private String callbackBaseUrl;

    /** 手机端可编辑：ONLYOFFICE 社区版的手机网页端不能编辑（会弹商业许可提示），只有引擎支持时才打开 */
    @Value("${docs.mobile_edit:false}")
    private boolean mobileEdit;

    /** 隐藏编辑器左上角标识与「关于」：只有引擎放开了品牌定制（授权版或不带附加条款的分支）才打开 */
    @Value("${docs.hide_branding:false}")
    private boolean hideBranding;

    /** 隐藏编辑器自带的聊天（IM 里协作不需要第二个聊天窗口；批注不受影响） */
    @Value("${docs.hide_chat:true}")
    private boolean hideChat;

    /** 网盘在对外入口上的路径前缀（NG 把 /pan/ 去掉前缀转给本服务的客户端端口） */
    @Value("${pan.public_path:/pan}")
    private String panPublicPath;

    /** 每个文件保留的历史版本数（含当前版本） */
    @Value("${pan.version.keep:30}")
    private int versionKeep;

    /** 下载链接有效期（秒） */
    @Value("${pan.download.ttl_seconds:600}")
    private int downloadTtlSeconds;

    /** 群成员关系缓存时间（秒）：退群后最长这么久才失去群分享的权限 */
    @Value("${pan.group_cache_seconds:60}")
    private int groupCacheSeconds;

    /** 网页会话（在线文档页面）有效期（秒） */
    @Value("${pan.web_session_ttl_seconds:43200}")
    private int webSessionTtlSeconds;

    /** 本服务自己签名用的密钥（下载链接、网页会话）；未配置时启动时随机生成（重启后旧链接失效） */
    @Value("${pan.sign_secret:}")
    private String signSecret;

    /**
     * 只读 PDF 预览缓存目录（手机端打开文档先转 PDF 再显示，比加载十几 MB 的编辑器省流量）。
     * 留空用系统临时目录（重启后失效，下次打开重新转）。
     */
    @Value("${docs.preview_dir:}")
    private String previewDir;

    /** 手机端打开文档时优先用 PDF 预览（只读显示）；关闭则仍走 ONLYOFFICE 编辑器 */
    @Value("${docs.mobile_pdf_preview:true}")
    private boolean mobilePdfPreview;
}
