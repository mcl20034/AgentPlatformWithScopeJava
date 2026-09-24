package com.agentplatform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.security")
public record SecurityProperties(int bcryptStrength,int maxFailedLogins,int lockMinutes) {
    public int effectiveMaxFailedLogins(){return maxFailedLogins>0?maxFailedLogins:5;}
    public int effectiveLockMinutes(){return lockMinutes>0?lockMinutes:15;}
}
