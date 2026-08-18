package com.yooooo.rag.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.yooooo.rag.dto.ApiResponse;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 提供简化的登录接口，用于生成演示用的用户上下文和令牌。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Slf4j
public class AuthController {
    private static final Map<String, UserProfile> DEMO_USERS = Map.of(
            "hr001",   new UserProfile(1L, "HR",   "MEMBER"),
            "tech001", new UserProfile(2L, "TECH", "MEMBER"),
            "admin",   new UserProfile(3L, "ALL",  "ADMIN")
    );

    @PostMapping("/login")
    public ApiResponse<String> login(@RequestBody LoginRequest req) {
        UserProfile profile = DEMO_USERS.get(req.getUsername());
        if (profile == null || !"demo123".equals(req.getPassword())) {
            return ApiResponse.error(401, "用户名或密码错误");
        }

        StpUtil.login(profile.userId());

        StpUtil.getSession().set("departmentId", profile.departmentId());
        StpUtil.getSession().set("role", profile.role());

        String token = StpUtil.getTokenValue();
        log.info("[Auth] 登录成功：username={}，userId={}，dept={}",
                req.getUsername(), profile.userId(), profile.departmentId());

        return ApiResponse.ok(token);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.ok(null);
    }
/**
 * 登录请求参数，包含用户、部门和角色信息。
 */
    @Data
    static class LoginRequest {
        private String username;
        private String password;
    }
/**
 * 登录成功后返回给前端的用户身份信息。
 */
    record UserProfile(Long userId, String departmentId, String role) {}
}
