/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.dashboard.rule.nacos;

import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.FlowRuleEntity;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.ParamFlowRuleEntity;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;

/**
 * @author Eric Zhao
 * @since 1.4.0
 */
@Configuration
public class NacosConfig {

    @Value("${nacos.server-addr}")
    private String nacosServerAddr;

    @Bean
    public Converter<List<FlowRuleEntity>, String> flowRuleEntityEncoder() {
        return JSON::toJSONString;
    }

    @Bean
    public Converter<String, List<FlowRuleEntity>> flowRuleEntityDecoder() {
        return s -> JSON.parseArray(s, FlowRuleEntity.class);
    }

    @Bean
    public Converter<List<com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.DegradeRuleEntity>, String> degradeRuleEntityEncoder() {
        return JSON::toJSONString;
    }

    @Bean
    public Converter<String, List<com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.DegradeRuleEntity>> degradeRuleEntityDecoder() {
        return s -> JSON.parseArray(s, com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.DegradeRuleEntity.class);
    }

    @Bean
    public Converter<List<com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.SystemRuleEntity>, String> systemRuleEntityEncoder() {
        return entities -> {
            if (entities == null) {
                return "[]";
            }
            // 提取纯 SystemRule 避免保存不必要的 entity 包装属性
            List<com.alibaba.csp.sentinel.slots.system.SystemRule> rules = entities.stream()
                    .map(com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.SystemRuleEntity::toRule)
                    .collect(Collectors.toList());
            return JSON.toJSONString(rules);
        };
    }

    @Bean
    public Converter<String, List<com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.SystemRuleEntity>> systemRuleEntityDecoder() {
        return s -> {
            List<com.alibaba.csp.sentinel.slots.system.SystemRule> rules = JSON.parseArray(s, com.alibaba.csp.sentinel.slots.system.SystemRule.class);
            if (rules == null) {
                return new java.util.ArrayList<>();
            }
            return rules.stream().map(rule -> {
                return com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.SystemRuleEntity.fromSystemRule(null, null, null, rule);
            }).collect(Collectors.toList());
        };
    }

    @Bean
    public Converter<List<com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.AuthorityRuleEntity>, String> authorityRuleEntityEncoder() {
        return entities -> {
            if (entities == null) {
                return "[]";
            }
            // 只序列化内部的 AuthorityRule，不序列化 Entity 外壳，避免嵌套在 "rule" 字段中导致客户端无法解析
            List<com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule> rules = entities.stream()
                    .map(com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.AuthorityRuleEntity::getRule)
                    .collect(Collectors.toList());
            return JSON.toJSONString(rules);
        };
    }

    @Bean
    public Converter<String, List<com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.AuthorityRuleEntity>> authorityRuleEntityDecoder() {
        return s -> {
            List<com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule> rules = JSON.parseArray(s, com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule.class);
            if (rules == null) {
                return new java.util.ArrayList<>();
            }
            return rules.stream().map(rule -> {
                com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.AuthorityRuleEntity entity = new com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.AuthorityRuleEntity();
                entity.setRule(rule);
                return entity;
            }).collect(Collectors.toList());
        };
    }

    @Bean
    public Converter<List<ParamFlowRuleEntity>, String> paramFlowRuleEntityEncoder() {
        // 关键修复：只序列化内部的 ParamFlowRule，不序列化 Entity 外壳
        // 否则客户端无法解析嵌套在 "rule" 字段里的规则数据
        return entities -> {
            if (entities == null) {
                return "[]";
            }
            List<ParamFlowRule> rules = entities.stream()
                    .map(ParamFlowRuleEntity::getRule)
                    .collect(Collectors.toList());
            return JSON.toJSONString(rules);
        };
    }

    @Bean
    public Converter<String, List<ParamFlowRuleEntity>> paramFlowRuleEntityDecoder() {
        // 从 Nacos 读取纯 ParamFlowRule JSON，再包装回 Entity 供 Dashboard 使用
        return s -> {
            List<ParamFlowRule> rules = JSON.parseArray(s, ParamFlowRule.class);
            if (rules == null) {
                return new java.util.ArrayList<>();
            }
            return rules.stream().map(rule -> {
                ParamFlowRuleEntity entity = new ParamFlowRuleEntity();
                entity.setRule(rule);
                return entity;
            }).collect(Collectors.toList());
        };
    }

    @Bean
    public ConfigService nacosConfigService() throws Exception {
//        return ConfigFactory.createConfigService("localhost");
        return ConfigFactory.createConfigService(nacosServerAddr);
    }
}
