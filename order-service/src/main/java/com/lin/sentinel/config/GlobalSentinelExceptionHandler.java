//package com.lin.sentinel.config;
//
//import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.BlockExceptionHandler;
//import com.alibaba.csp.sentinel.slots.block.BlockException;
//import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
//import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import org.springframework.stereotype.Component;
//
//@Component
//public class GlobalSentinelExceptionHandler implements BlockExceptionHandler {
//
//    @Override
//    public void handle(HttpServletRequest request, HttpServletResponse response, BlockException e) throws Exception {
//        response.setStatus(429);
//        response.setContentType("application/json;charset=utf-8");
//
//        // 拦截系统规则触发
//        if (e instanceof SystemBlockException) {
//            response.getWriter().write("{\"code\":500, \"msg\":\"【系统底层装甲生效】您的服务器遭遇海量流量，核心指标已超过安全水位，系统已自动剥离多余请求以求生！\"}");
//        }
//        // 拦截授权规则触发
//        else if (e instanceof AuthorityException) {
//            response.setStatus(403);
//            response.getWriter().write("{\"code\":403, \"msg\":\"【授权管控拦截】您不在白名单中，或者是黑名单成员，已被决绝访问！\"}");
//        }
//        // 其他常规拦截
//        else {
//            response.getWriter().write("{\"code\":429, \"msg\":\"系统极度繁忙，请稍后再试\"}");
//        }
//    }
//}
