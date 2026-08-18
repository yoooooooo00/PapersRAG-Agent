package com.yooooo.rag.security;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/**
 * 配置接口安全策略和 Sa-Token 登录拦截规则。
 */
@Configuration
@Slf4j
public class SecurityConfig {
    @Bean
    public SaServletFilter saServletFilter() {
        return new SaServletFilter()
                .addInclude("/**")
                .addExclude("/actuator/**", "/api/v1/auth/**")
                .setAuth(obj -> {
                    SaRouter.match("/api/**", () -> StpUtil.checkLogin());
                })
                .setError(e -> {
                    SaHolder.getResponse().setStatus(HttpStatus.UNAUTHORIZED.value());
                    return "{\"code\":401,\"message\":\"请先登录\"}";
                });
    }
}
