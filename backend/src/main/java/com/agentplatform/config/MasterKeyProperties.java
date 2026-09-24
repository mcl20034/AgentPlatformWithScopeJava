package com.agentplatform.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("platform.secrets")
public record MasterKeyProperties(String keyId, String masterKey, String masterKeyFile) {}
