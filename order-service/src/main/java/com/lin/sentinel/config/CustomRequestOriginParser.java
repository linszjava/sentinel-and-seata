package com.lin.sentinel.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.RequestOriginParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class CustomRequestOriginParser implements RequestOriginParser {

    @Override
    public String parseOrigin(HttpServletRequest request) {
        // 从 HTTP Header 中获取 origin 字段作为身份标识
        String origin = request.getHeader("origin");
        
        // 如果没有携带这个头，视为 unknown 来源
        if (origin == null || origin.isEmpty()) {
            return "unknown"; 
        }
        return origin;
    }
}
