package com.agentplatform.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/operations")
public class OperationsController {
    private final JdbcTemplate jdbc;
    public OperationsController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/summary") public Map<String,Object> summary(){
        Map<String,Object> task=jdbc.queryForMap("""
          SELECT COUNT(*) total,
          COALESCE(SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END),0) completed,
          COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) failed,
          COALESCE(SUM(CASE WHEN status IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END),0) active
          FROM analysis_task WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24' HOUR
          """);
        Map<String,Object> model=jdbc.queryForMap("SELECT COUNT(*) calls,COALESCE(SUM(total_tokens),0) tokens,COALESCE(AVG(duration_ms),0) average_duration_ms FROM model_call_log WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24' HOUR");
        return Map.of("tasks",task,"models",model,"generatedAt",com.agentplatform.common.DatabaseTime.now());
    }
    @GetMapping("/health") public Map<String,Object> health(){
        boolean database=Boolean.TRUE.equals(jdbc.queryForObject("SELECT TRUE",Boolean.class));
        boolean vector=false;try{Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM pg_extension WHERE extname='vector'",Integer.class);vector=n!=null&&n>0;}catch(Exception ignored){}
        Integer models=jdbc.queryForObject("SELECT COUNT(*) FROM model_config WHERE enabled=TRUE",Integer.class);
        Integer sources=jdbc.queryForObject("SELECT COUNT(*) FROM data_source WHERE enabled=TRUE",Integer.class);
        Integer knowledge=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base WHERE enabled=TRUE",Integer.class);
        return Map.of("database",database?"UP":"DOWN","pgvector",vector?"UP":"NOT_INSTALLED","enabledModels",models,"enabledSources",sources,"enabledKnowledgeBases",knowledge);
    }
    @GetMapping("/tasks") public List<Map<String,Object>> tasks(@RequestParam(defaultValue="100") int limit){return jdbc.queryForList("SELECT t.id,t.status,t.stage,t.error_code,t.created_at,t.updated_at,c.title,u.username FROM analysis_task t JOIN chat_conversation c ON c.id=t.conversation_id JOIN app_user u ON u.id=c.user_id ORDER BY t.created_at DESC LIMIT ?",bounded(limit));}
    @GetMapping("/model-calls") public List<Map<String,Object>> modelCalls(@RequestParam(defaultValue="100") int limit){return jdbc.queryForList("SELECT id,task_id,provider,model_name,purpose,status,duration_ms,prompt_tokens,completion_tokens,total_tokens,error_code,occurred_at FROM model_call_log ORDER BY occurred_at DESC LIMIT ?",bounded(limit));}
    @GetMapping("/audit") public List<Map<String,Object>> audit(@RequestParam(defaultValue="100") int limit){return jdbc.queryForList("SELECT id,actor_username,action,target_type,result,request_id,ip_address,details_json,occurred_at FROM audit_log ORDER BY occurred_at DESC LIMIT ?",bounded(limit));}
    private int bounded(int value){return Math.max(1,Math.min(value,500));}
}
