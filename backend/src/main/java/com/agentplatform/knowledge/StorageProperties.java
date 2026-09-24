package com.agentplatform.knowledge;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("platform.storage") public record StorageProperties(String root) {}
