package com.yooooo.rag.security;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 知识库认证拦截器
 * 将Sa-Token登录用户信息读取并存入请求线程上下文UserContext
 * 请求结束后自动清除上下文，防止线程复用造成信息污染
 */
@Component
public class KbAuthInterceptor implements HandlerInterceptor {
    /**
     * Controller执行前预处理
     * 从Sa-Token会话中获取登录用户ID、部门ID、角色信息，设置到线程上下文
     * 未登录用户直接放行，不设置用户上下文
     *
     * @param request 请求对象
     * @param response 响应对象
     * @param handler 处理器对象
     * @return true 继续执行后续流程
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 判断当前用户是否未登录，未登录直接放行
        if (!StpUtil.isLogin()) {
            return true;
        }

        // 获取登录用户唯一标识
        String userId = String.valueOf(StpUtil.getLoginId());
        // 从Sa-Token Session获取部门ID
        Object deptObj = StpUtil.getSession().get("departmentId");
        // 从Sa-Token Session获取用户角色
        Object roleObj = StpUtil.getSession().get("role");

        // 部门ID为空则赋值空字符串
        String deptId = deptObj != null ? deptObj.toString() : "";
        // 角色为空时默认赋值普通成员 MEMBER
        String role = roleObj != null ? roleObj.toString() : "MEMBER";

        // 将用户信息存入当前请求线程上下文
        UserContext.set(Long.parseLong(userId), deptId, role);
        return true;
    }

    /**
     * 请求完全处理完毕（视图渲染完成）后回调
     * 清除线程上下文用户信息，避免线程池复用线程引发用户信息串号问题
     *
     * @param request 请求对象
     * @param response 响应对象
     * @param handler 处理器对象
     * @param ex 请求过程抛出的异常
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
