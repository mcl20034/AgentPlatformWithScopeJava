package com.agentplatform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.bootstrap")
public record BootstrapProperties(String adminUsername, String adminPassword, String adminPasswordFile) {}
