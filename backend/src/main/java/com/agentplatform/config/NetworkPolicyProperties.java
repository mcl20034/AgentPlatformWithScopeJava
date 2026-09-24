package com.agentplatform.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
@ConfigurationProperties("platform.network")
public record NetworkPolicyProperties(List<String> allowedHosts) {
 public boolean allows(String host){return host!=null&&allowedHosts()!=null&&allowedHosts().stream().anyMatch(v->v.equalsIgnoreCase(host));}
}
