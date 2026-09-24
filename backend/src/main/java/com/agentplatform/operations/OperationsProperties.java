package com.agentplatform.operations;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.operations")
public record OperationsProperties(int maxActiveTasksPerUser, int maxTasksPerHourPerUser) {}
