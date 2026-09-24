package com.agentplatform.identity;

import com.agentplatform.common.BusinessException;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/users/{userId}/grants")
public class AdminResourceGrantController {
    private final JdbcTemplate jdbc;private final UserRepository users;private final AuditService audit;
    public AdminResourceGrantController(JdbcTemplate jdbc,UserRepository users,AuditService audit){this.jdbc=jdbc;this.users=users;this.audit=audit;}

    @GetMapping public Map<String,Object> get(@PathVariable UUID userId){
        AppUser user=users.findById(userId).orElseThrow(()->new BusinessException(404,"USER_NOT_FOUND","账号不存在"));
        return Map.of("implicitAll",user.role()==Role.ADMIN,
                "models",catalog("SELECT id,display_name name,enabled FROM model_config ORDER BY display_name",userId,ResourceAuthorizationService.Type.MODEL),
                "dataSources",catalog("SELECT id,name,enabled FROM data_source ORDER BY name",userId,ResourceAuthorizationService.Type.DATA_SOURCE),
                "knowledgeBases",catalog("SELECT id,name,enabled FROM knowledge_base ORDER BY name",userId,ResourceAuthorizationService.Type.KNOWLEDGE_BASE));
    }
    @PutMapping @Transactional public void replace(@PathVariable UUID userId,@Valid @RequestBody GrantsRequest body,Authentication authentication){
        AppUser user=users.findById(userId).orElseThrow(()->new BusinessException(404,"USER_NOT_FOUND","账号不存在"));
        if(user.role()==Role.ADMIN)throw new BusinessException(422,"ADMIN_HAS_ALL_RESOURCES","管理员默认拥有全部资源权限");
        validate("model_config",body.models);validate("data_source",body.dataSources);validate("knowledge_base",body.knowledgeBases);
        jdbc.update("DELETE FROM user_resource_grant WHERE user_id=?",userId.toString());
        PlatformPrincipal actor=(PlatformPrincipal)authentication.getPrincipal();
        insert(userId,actor.id(),ResourceAuthorizationService.Type.MODEL,body.models);insert(userId,actor.id(),ResourceAuthorizationService.Type.DATA_SOURCE,body.dataSources);insert(userId,actor.id(),ResourceAuthorizationService.Type.KNOWLEDGE_BASE,body.knowledgeBases);
        audit.record(actor.id(),"REPLACE_RESOURCE_GRANTS","USER",userId,"SUCCESS");
    }
    private List<Map<String,Object>> catalog(String sql,UUID userId,ResourceAuthorizationService.Type type){List<Map<String,Object>> rows=jdbc.queryForList(sql);Set<String> granted=new HashSet<>(jdbc.queryForList("SELECT resource_id FROM user_resource_grant WHERE user_id=? AND resource_type=?",String.class,userId.toString(),type.name()));rows.forEach(row->row.put("granted",granted.contains(String.valueOf(row.get("id")))));return rows;}
    private void validate(String table,List<UUID> ids){if(!Set.of("model_config","data_source","knowledge_base").contains(table))throw new IllegalArgumentException();for(UUID id:safe(ids)){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",Integer.class,id.toString());if(count==null||count==0)throw new BusinessException(422,"RESOURCE_NOT_FOUND","授权资源不存在");}}
    private void insert(UUID userId,UUID actor,ResourceAuthorizationService.Type type,List<UUID> ids){for(UUID id:new LinkedHashSet<>(safe(ids)))jdbc.update("INSERT INTO user_resource_grant(user_id,resource_type,resource_id,granted_by,granted_at) VALUES(?,?,?,?,?)",userId.toString(),type.name(),id.toString(),actor.toString(),com.agentplatform.common.DatabaseTime.now());}
    private static List<UUID> safe(List<UUID> ids){return ids==null?List.of():ids;}
    public record GrantsRequest(List<UUID> models,List<UUID> dataSources,List<UUID> knowledgeBases){}
}
