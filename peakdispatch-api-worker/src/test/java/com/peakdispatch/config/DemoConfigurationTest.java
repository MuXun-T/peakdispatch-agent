package com.peakdispatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class DemoConfigurationTest {
    @Test
    void demoProfileConfiguration_loadsCourierRegionScopes() throws Exception {
        var propertySource = new YamlPropertySourceLoader()
                .load("application-demo", new ClassPathResource("application-demo.yml"))
                .get(0);

        assertThat(propertySource.getProperty("peakdispatch.demo.courier-pool.region-scopes[0]"))
                .isEqualTo("${PD_DEMO_COURIER_REGION_SCOPE:demo-region}");
    }
}
