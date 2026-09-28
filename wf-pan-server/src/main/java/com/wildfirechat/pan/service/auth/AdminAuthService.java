package com.wildfirechat.pan.service.auth;

import com.wildfirechat.pan.entity.PanGlobalAdmin;
import com.wildfirechat.pan.entity.SysConfig;
import com.wildfirechat.pan.exception.BusinessException;
import com.wildfirechat.pan.repository.PanGlobalAdminRepository;
import com.wildfirechat.pan.repository.SysConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 管理后台账号：全局管理员（IM 用户ID）+ 各自的登录密码
 */
@Service
@Slf4j
public class AdminAuthService {

    /**
     * 旧版本所有管理员共用的密码哈希，仅对尚未设置个人密码的管理员生效
     */
    private static final String LEGACY_PASSWORD_KEY = "admin.default.password_hash";
    private static final String LEGACY_DEFAULT_PASSWORD = "admin123";
    private static final String INITIAL_ADMIN_ID = "admin";

    private final PanGlobalAdminRepository globalAdminRepository;
    private final SysConfigRepository sysConfigRepository;
    private final LoginThrottle loginThrottle;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    // 用户不存在时也做一次哈希比较，避免通过响应时间枚举管理员账号
    private final String dummyHash = passwordEncoder.encode("dummy-password");

    public AdminAuthService(PanGlobalAdminRepository globalAdminRepository,
                            SysConfigRepository sysConfigRepository,
                            LoginThrottle loginThrottle) {
        this.globalAdminRepository = globalAdminRepository;
        this.sysConfigRepository = sysConfigRepository;
        this.loginThrottle = loginThrottle;
    }

    /**
     * 验证管理员登录，同一 IP + 用户名连续失败过多时拒绝
     */
    public boolean login(String userId, String password, String clientIp) {
        String throttleKey = clientIp + "|" + userId;
        if (loginThrottle.isBlocked(throttleKey)) {
            throw new BusinessException("登录失败次数过多，请稍后再试");
        }
        if (!passwordMatches(userId, password)) {
            loginThrottle.recordFailure(throttleKey);
            log.warn("Admin login failed: {}, ip: {}", userId, clientIp);
            return false;
        }
        loginThrottle.reset(throttleKey);
        return true;
    }

    @Transactional
    public void changePassword(String userId, String oldPassword, String newPassword) {
        if (!passwordMatches(userId, oldPassword)) {
            throw new BusinessException("旧密码错误");
        }
        PanGlobalAdmin admin = requireAdmin(userId);
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        globalAdminRepository.save(admin);
    }

    public List<PanGlobalAdmin> listAdmins() {
        return globalAdminRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public PanGlobalAdmin addAdmin(String userId, String username, String password, String createdBy) {
        if (globalAdminRepository.existsByUserId(userId)) {
            throw new BusinessException("该用户已是管理员");
        }
        PanGlobalAdmin admin = new PanGlobalAdmin();
        admin.setUserId(userId);
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setCreatedBy(createdBy);
        return globalAdminRepository.save(admin);
    }

    @Transactional
    public PanGlobalAdmin removeAdmin(String userId) {
        PanGlobalAdmin admin = requireAdmin(userId);
        if (globalAdminRepository.count() <= 1) {
            throw new BusinessException("至少保留一个管理员");
        }
        globalAdminRepository.delete(admin);
        return admin;
    }

    /**
     * 没有任何管理员时创建初始管理员。未配置初始密码时生成随机密码并输出到日志（仅此一次）。
     */
    @Transactional
    public void ensureInitialAdmin(String configuredPassword) {
        if (globalAdminRepository.count() > 0) {
            warnIfLegacyDefaultPassword();
            return;
        }
        boolean generated = !StringUtils.hasText(configuredPassword);
        String password = generated ? randomPassword() : configuredPassword;
        addAdmin(INITIAL_ADMIN_ID, "系统管理员", password, "SYSTEM");
        if (generated) {
            log.warn("已创建初始管理员 {}，随机密码: {} （仅显示一次，请登录后立即修改，并添加实际的IM用户为管理员后删除该账号）",
                INITIAL_ADMIN_ID, password);
        } else {
            log.info("已创建初始管理员 {}，密码来自配置 pan.admin.initial_password", INITIAL_ADMIN_ID);
        }
    }

    private boolean passwordMatches(String userId, String password) {
        String hash = globalAdminRepository.findByUserId(userId)
            .map(this::passwordHashOf)
            .orElse(null);
        if (hash == null) {
            passwordEncoder.matches(password, dummyHash);
            return false;
        }
        return passwordEncoder.matches(password, hash);
    }

    private String passwordHashOf(PanGlobalAdmin admin) {
        if (admin.getPasswordHash() != null) {
            return admin.getPasswordHash();
        }
        return sysConfigRepository.findByConfigKey(LEGACY_PASSWORD_KEY)
            .map(SysConfig::getConfigValue)
            .orElse(null);
    }

    private PanGlobalAdmin requireAdmin(String userId) {
        return globalAdminRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException("管理员不存在"));
    }

    private void warnIfLegacyDefaultPassword() {
        sysConfigRepository.findByConfigKey(LEGACY_PASSWORD_KEY)
            .filter(config -> passwordEncoder.matches(LEGACY_DEFAULT_PASSWORD, config.getConfigValue()))
            .ifPresent(config -> log.warn("管理员共享密码仍是默认值 {}，请尽快登录并修改密码", LEGACY_DEFAULT_PASSWORD));
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
