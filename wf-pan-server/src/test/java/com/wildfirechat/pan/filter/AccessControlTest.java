package com.wildfirechat.pan.filter;

import com.wildfirechat.pan.config.ProxiedRequestValve;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.service.SignService;
import com.wildfirechat.pan.service.auth.AdminAuthService;
import com.wildfirechat.pan.service.auth.ClientAuthService;
import com.wildfirechat.pan.util.Hs256Jwt;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessControlTest {

    private static final int ADMIN_PORT = 8080;
    private static final int CLIENT_PORT = 8081;
    private static final String INITIAL_PASSWORD = "initial-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private PanGlobalAdminRepository globalAdminRepository;

    @Autowired
    private SignService signService;

    @MockBean
    private ClientAuthService clientAuthService;

    @BeforeEach
    void setUp() {
        when(clientAuthService.validateAuthCode("valid-code")).thenReturn("alice");
    }

    private static RequestPostProcessor port(int port) {
        return request -> {
            request.setLocalPort(port);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder login(String username, String password) {
        return post("/api/auth/login")
            .with(port(ADMIN_PORT))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private MockHttpSession loginAsAdmin(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(login(username, password))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sessionId").doesNotExist())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void adminApiRequiresSession() throws Exception {
        mockMvc.perform(get("/api/global-admins").with(port(ADMIN_PORT)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/global-admins").with(port(ADMIN_PORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"mallory\",\"password\":\"12345678\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/alice").with(port(ADMIN_PORT)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/logs/clear").with(port(ADMIN_PORT)))
            .andExpect(status().isUnauthorized());

        assertThat(globalAdminRepository.existsByUserId("mallory")).isFalse();
    }

    @Test
    void adminApiWorksAfterLogin() throws Exception {
        MockHttpSession session = loginAsAdmin("admin", INITIAL_PASSWORD);

        mockMvc.perform(get("/api/global-admins").with(port(ADMIN_PORT)).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/auth/info").with(port(ADMIN_PORT)).session(session))
            .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = loginAsAdmin("admin", INITIAL_PASSWORD);

        mockMvc.perform(post("/api/auth/logout").with(port(ADMIN_PORT)).session(session))
            .andExpect(jsonPath("$.code").value(0));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void addedAdminLogsInWithOwnPasswordAndIsRevokedImmediatelyWhenRemoved() throws Exception {
        MockHttpSession root = loginAsAdmin("admin", INITIAL_PASSWORD);
        mockMvc.perform(post("/api/global-admins").with(port(ADMIN_PORT)).session(root)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"bob\",\"password\":\"bob-password\"}"))
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(login("bob", INITIAL_PASSWORD)).andExpect(jsonPath("$.code").value(500));
        MockHttpSession bob = loginAsAdmin("bob", "bob-password");

        mockMvc.perform(delete("/api/global-admins/bob").with(port(ADMIN_PORT)).session(root))
            .andExpect(jsonPath("$.code").value(0));
        assertThat(globalAdminRepository.existsByUserId("bob")).isFalse();

        mockMvc.perform(get("/api/global-admins").with(port(ADMIN_PORT)).session(bob))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedLoginFailuresAreThrottled() throws Exception {
        adminAuthService.addAdmin("carol", null, "carol-password", "test");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login("carol", "wrong-password")).andExpect(jsonPath("$.message").value("用户名或密码错误"));
        }

        mockMvc.perform(login("carol", "carol-password"))
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("登录失败次数过多，请稍后再试"));
    }

    @Test
    void clientApiIsNotReachableFromAdminPort() throws Exception {
        mockMvc.perform(post("/api/v1/spaces/list").with(port(ADMIN_PORT)).header("authCode", "valid-code"))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminApiAndPagesAreNotReachableFromClientPort() throws Exception {
        mockMvc.perform(get("/api/global-admins").with(port(CLIENT_PORT)).header("authCode", "valid-code"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/auth/login").with(port(CLIENT_PORT)))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/dashboard").with(port(CLIENT_PORT)))
            .andExpect(status().isNotFound());
    }

    @Test
    void clientApiRequiresValidAuthCode() throws Exception {
        mockMvc.perform(post("/api/v1/spaces/list").with(port(CLIENT_PORT)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(post("/api/v1/spaces/list").with(port(CLIENT_PORT)).header("authCode", "forged"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1002));
        mockMvc.perform(post("/api/v1/spaces/list").with(port(CLIENT_PORT)).header("authCode", "valid-code"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void corsPreflightIsAllowedWithoutAuthCode() throws Exception {
        mockMvc.perform(options("/api/v1/spaces/list").with(port(CLIENT_PORT))
                .header("Origin", "https://web.example.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authCode,Content-Type"))
            .andExpect(status().isOk());
    }

    @Test
    void docPageSessionAuthenticatesClientApiOnlyWithCsrfHeader() throws Exception {
        Cookie session = new Cookie(ClientAuthFilter.WEB_SESSION_COOKIE, signService.issueWebSession("alice"));

        mockMvc.perform(post("/api/v1/spaces/list").with(port(CLIENT_PORT)).cookie(session)
                .header(ClientAuthFilter.WEB_HEADER, "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/v1/spaces/list").with(port(CLIENT_PORT)).cookie(session))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1001));
        Cookie forged = new Cookie(ClientAuthFilter.WEB_SESSION_COOKIE, session.getValue() + "x");
        mockMvc.perform(post("/api/v1/spaces/list").with(port(CLIENT_PORT)).cookie(forged)
                .header(ClientAuthFilter.WEB_HEADER, "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void docWebPathsUseTheirOwnAuthOnClientPort() throws Exception {
        mockMvc.perform(get("/dl/1").with(port(CLIENT_PORT))
                .param("v", "1").param("u", "alice").param("e", "9999999999").param("s", "forged"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/docs/file/1").with(port(CLIENT_PORT)))
            .andExpect(status().isForbidden());
    }

    @Test
    void onlyofficeCallbackRejectsProxiedRequests() throws Exception {
        mockMvc.perform(get("/internal/docs/file/1").with(port(CLIENT_PORT)).header("X-Forwarded-For", "1.2.3.4"))
            .andExpect(status().isNotFound());
        // native 转发头策略下 RemoteIpValve 会删掉 X-Forwarded-For，以 ProxiedRequestValve 的标记为准
        mockMvc.perform(get("/internal/docs/file/1").with(port(CLIENT_PORT))
                .requestAttr(ProxiedRequestValve.PROXIED_ATTRIBUTE, Boolean.TRUE))
            .andExpect(status().isNotFound());
    }

    @Test
    void docWebPathsAreNotReachableFromAdminPort() throws Exception {
        mockMvc.perform(get("/dl/1").with(port(ADMIN_PORT)))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/doc/session").with(port(ADMIN_PORT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"authCode\":\"valid-code\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/internal/docs/file/1").with(port(ADMIN_PORT)))
            .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder editorFile(long fileId, int versionNo, long expire, String sig) {
        return get("/internal/docs/file/" + fileId).with(port(CLIENT_PORT))
            .param("v", String.valueOf(versionNo))
            .param("e", String.valueOf(expire))
            .param("s", sig);
    }

    @Test
    void onlyofficeFileUrlIsBoundToFileAndVersion() throws Exception {
        // 任何经 ONLYOFFICE 签名的令牌都不足以取文件（它也会为用户指定的地址签令牌）
        String jwt = "Bearer " + Hs256Jwt.sign(Map.of("payload", Map.of("url", "http://attacker.example.com/x")),
            "test-jwt-secret");
        long fileId = 987654321L;
        long expire = System.currentTimeMillis() / 1000 + 600;
        String sig = signService.signEditorFile(fileId, 1, expire);

        // 签名和 JWT 都有效：通过鉴权（文件不存在，404）
        mockMvc.perform(editorFile(fileId, 1, expire, sig).header("Authorization", jwt))
            .andExpect(status().isNotFound());

        mockMvc.perform(editorFile(fileId + 1, 1, expire, sig).header("Authorization", jwt))
            .andExpect(status().isForbidden());
        mockMvc.perform(editorFile(fileId, 2, expire, sig).header("Authorization", jwt))
            .andExpect(status().isForbidden());
        long expired = System.currentTimeMillis() / 1000 - 1;
        mockMvc.perform(editorFile(fileId, 1, expired, signService.signEditorFile(fileId, 1, expired))
                .header("Authorization", jwt))
            .andExpect(status().isForbidden());
        // 下载链接的签名不能用来取编辑器文件
        mockMvc.perform(editorFile(fileId, 1, expire, signService.signDownload(fileId, 1, "", expire))
                .header("Authorization", jwt))
            .andExpect(status().isForbidden());
        // 只有地址、没有 ONLYOFFICE 的 JWT
        mockMvc.perform(editorFile(fileId, 1, expire, sig))
            .andExpect(status().isForbidden());
    }
}
