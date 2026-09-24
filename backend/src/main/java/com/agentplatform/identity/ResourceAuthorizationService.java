package com.agentplatform.identity;

import com.agentplatform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ResourceAuthorizationService {
    public enum Type { MODEL, DATA_SOURCE, KNOWLEDGE_BASE }
    private final JdbcTemplate jdbc;
    public ResourceAuthorizationService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public boolean canAccess(PlatformPrincipal principal,Type type,UUID id){
        if(principal.role()==Role.ADMIN)return true;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM user_resource_grant WHERE user_id=? AND resource_type=? AND resource_id=?",Integer.class,principal.id().toString(),type.name(),id.toString());
        return count!=null&&count>0;
    }
    public void require(PlatformPrincipal principal,Type type,UUID id){if(!canAccess(principal,type,id))throw new BusinessException(403,"RESOURCE_FORBIDDEN","您没有使用所选资源的权限");}
    public Set<String> accessibleIds(PlatformPrincipal principal,Type type){
        if(principal.role()==Role.ADMIN)return null;
        return new LinkedHashSet<>(jdbc.queryForList("SELECT resource_id FROM user_resource_grant WHERE user_id=? AND resource_type=?",String.class,principal.id().toString(),type.name()));
    }
}
