package com.peakdispatch.application.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("test")
public class AgentTestConcurrencyConfiguration {
    @Bean
    AgentExecutionConcurrency testAgentExecutionConcurrency() {
        return AgentExecutionConcurrency.noop();
    }
}
