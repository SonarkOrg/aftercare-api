package com.aftercare.aftercareapi.support;

import jakarta.persistence.Entity;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.model.naming.ImplicitNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the raw Hibernate DDL for every entity, for PostgreSQL and H2, into target/schema/.
 * It is how db/migration/{vendor}/V1__baseline.sql was produced (then post-processed, see README "Migrations").
 * Not part of the normal build; run with: mvn -Dgroups=schema-export -Dtests.excluded.groups=none test
 */
@Tag("schema-export")
class SchemaExportTool {

    @Test
    void export() throws Exception {
        Files.createDirectories(Path.of("target/schema"));
        export("org.hibernate.dialect.PostgreSQLDialect", "target/schema/postgresql.sql");
        export("org.hibernate.dialect.H2Dialect", "target/schema/h2.sql");
    }

    private void export(String dialect, String file) throws Exception {
        Files.deleteIfExists(Path.of(file));
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", dialect)
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", file)
                .applySetting("hibernate.format_sql", "true")
                .applySetting("hibernate.hbm2ddl.delimiter", ";")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .applySetting("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName())
                .applySetting("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName())
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            for (BeanDefinition bd : scanner.findCandidateComponents("com.aftercare.aftercareapi.Models")) {
                sources.addAnnotatedClass(Class.forName(bd.getBeanClassName()));
            }
            // building the session factory runs the script-only schema generation (no database connection)
            sources.buildMetadata().buildSessionFactory().close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
