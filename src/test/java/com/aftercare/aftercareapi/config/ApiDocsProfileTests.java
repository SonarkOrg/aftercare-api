package com.aftercare.aftercareapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** API docs and Swagger UI are off by default and on only in application-local.yml (read from src/main: test resources shadow application.yml). */
class ApiDocsProfileTests {

    private static Properties load(String file) {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new FileSystemResource("src/main/resources/" + file));
        return y.getObject();
    }

    @Test
    void disabledByDefaultEnabledOnlyForLocal() {
        Properties base = load("application.yml");
        assertEquals("false", base.getProperty("springdoc.api-docs.enabled"));
        assertEquals("false", base.getProperty("springdoc.swagger-ui.enabled"));
        Properties local = load("application-local.yml");
        assertEquals("true", local.getProperty("springdoc.api-docs.enabled"));
        assertEquals("true", local.getProperty("springdoc.swagger-ui.enabled"));
        for (String other : new String[]{"application-dev.yml", "application-qa.yml", "application-staging.yml", "application-prod.yml"}) {
            Properties p = load(other);
            assertNull(p.getProperty("springdoc.api-docs.enabled"), other);
            assertNull(p.getProperty("springdoc.swagger-ui.enabled"), other);
        }
    }
}
