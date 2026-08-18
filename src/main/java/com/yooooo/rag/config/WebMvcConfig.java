package com.yooooo.rag.config;

import com.yooooo.rag.security.KbAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置哪些接口需要经过 KbAuthInterceptor，从而让业务代码可以通过 UserContext 获取当前登录用户、部门和角色。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final KbAuthInterceptor kbAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(kbAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**");
    }
}
