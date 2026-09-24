package com.agentplatform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.security")
public record SecurityProperties(int bcryptStrength) {}
