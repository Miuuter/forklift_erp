package com.example.forklift_erp.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

@Component("buildVersion")
public class BuildVersionHealthIndicator implements HealthIndicator {
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;

    public BuildVersionHealthIndicator(
            ObjectProvider<BuildProperties> buildProperties,
            ObjectProvider<GitProperties> gitProperties
    ) {
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
    }

    @Override
    public Health health() {
        BuildProperties build = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();
        return Health.up()
                .withDetail("version", build == null ? "development" : build.getVersion())
                .withDetail("commit", git == null ? "unknown" : git.getShortCommitId())
                .build();
    }
}
