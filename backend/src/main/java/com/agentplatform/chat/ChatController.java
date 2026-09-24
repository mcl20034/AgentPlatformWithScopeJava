package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import com.agentplatform.identity.PlatformPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final JdbcTemplate jdbc;
    private final ChatAnalysisService analysis;
    private final ChatTaskEvents events;
    private final ObjectMapper json;

    public ChatController(JdbcTemplate jdbc, ChatAnalysisService analysis, ChatTaskEvents events, ObjectMapper json) {
        this.jdbc = jdbc; this.analysis = analysis; this.events = events; this.json=json;
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of(
                "models", jdbc.queryForList("SELECT id,display_name name FROM model_config WHERE enabled=TRUE ORDER BY display_name"),
                "sources", jdbc.queryForList("SELECT id,name,type FROM data_source WHERE enabled=TRUE AND type IN ('MYSQL','ELASTICSEARCH') ORDER BY type,name"),
                "knowledgeBases",jdbc.queryForList("SELECT id,name,'KNOWLEDGE' type FROM knowledge_base WHERE enabled=TRUE AND active_generation_id IS NOT NULL ORDER BY name")
        );
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(Authentication authentication) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.id,c.title,c.model_id,c.source_id,d.type source_type,c.created_at,c.updated_at,(SELECT t.status FROM analysis_task t WHERE t.conversation_id=c.id ORDER BY t.created_at DESC LIMIT 1) last_status FROM chat_conversation c JOIN data_source d ON d.id=c.source_id WHERE c.user_id=? ORDER BY c.updated_at DESC", actor(authentication).id().toString());for(Map<String,Object> row:rows)row.put("knowledge_base_ids",jdbc.queryForList("SELECT kb_id FROM chat_conversation_knowledge WHERE conversation_id=? ORDER BY kb_id",String.class,String.valueOf(row.get("id"))));return rows;
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ConversationRequest request, Authentication authentication) {
        requireEnabled("model_config", request.modelId);
        requireEnabled("data_source", request.sourceId);
        List<UUID> knowledgeIds=request.knowledgeBaseIds==null?List.of():request.knowledgeBaseIds.stream().distinct().limit(5).toList();for(UUID kbId:knowledgeIds)requireEnabled("knowledge_base",kbId);
        UUID id = UUID.randomUUID(); var now = com.agentplatform.common.DatabaseTime.now();
        String title = request.title == null || request.title.isBlank() ? "新会话" : request.title.trim();
        jdbc.update("INSERT INTO chat_conversation(id,user_id,title,model_id,source_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                id.toString(), actor(authentication).id().toString(), title, request.modelId.toString(), request.sourceId.toString(), now, now);
        for(UUID kbId:knowledgeIds)jdbc.update("INSERT INTO chat_conversation_knowledge(conversation_id,kb_id) VALUES(?,?)",id.toString(),kbId.toString());
        return Map.of("id", id.toString(), "title", title);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable UUID id, Authentication authentication) {
        ownConversation(id, authentication);
        return jdbc.queryForList("SELECT m.id,m.role,m.content,m.sql_text,m.query_language,m.result_json,m.evidence_json,m.duration_ms,m.status,m.created_at,t.id task_id,t.stage task_stage,t.error_code FROM chat_message m LEFT JOIN analysis_task t ON t.assistant_message_id=m.id WHERE m.conversation_id=? ORDER BY m.created_at,m.id", id.toString());
    }

    @PutMapping("/conversations/{id}") public void rename(@PathVariable UUID id,@Valid @RequestBody RenameRequest request,Authentication authentication){ownConversation(id,authentication);jdbc.update("UPDATE chat_conversation SET title=?,updated_at=? WHERE id=?",request.title.trim(),com.agentplatform.common.DatabaseTime.now(),id.toString());}
    @DeleteMapping("/conversations/{id}") @Transactional public void deleteConversation(@PathVariable UUID id,Authentication authentication){ownConversation(id,authentication);jdbc.update("DELETE FROM analysis_task_event WHERE task_id IN (SELECT id FROM analysis_task WHERE conversation_id=?)",id.toString());jdbc.update("DELETE FROM analysis_task WHERE conversation_id=?",id.toString());jdbc.update("DELETE FROM chat_message WHERE conversation_id=?",id.toString());jdbc.update("DELETE FROM chat_conversation WHERE id=?",id.toString());}

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> send(@PathVariable UUID id, @Valid @RequestBody MessageRequest request, Authentication authentication) {
        ownConversation(id, authentication);
        Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, id.toString());
        if (running != null && running > 0) throw new BusinessException(409, "TASK_ALREADY_RUNNING", "当前会话已有任务正在执行");
        UUID userMessage = UUID.randomUUID(), assistantMessage = UUID.randomUUID(), task = UUID.randomUUID();
        var now = com.agentplatform.common.DatabaseTime.now();
        jdbc.update("INSERT INTO chat_message(id,conversation_id,role,content,status,created_at) VALUES(?,?,'USER',?,'COMPLETED',?)", userMessage.toString(), id.toString(), request.content.trim(), now);
        jdbc.update("INSERT INTO chat_message(id,conversation_id,role,content,status,created_at) VALUES(?,?,'ASSISTANT','','RUNNING',?)", assistantMessage.toString(), id.toString(), now);
        jdbc.update("INSERT INTO analysis_task(id,conversation_id,user_message_id,assistant_message_id,status,stage,created_at,updated_at) VALUES(?,?,?,?,'QUEUED','QUEUED',?,?)",
                task.toString(), id.toString(), userMessage.toString(), assistantMessage.toString(), now, now);
        jdbc.update("UPDATE chat_conversation SET title=CASE WHEN title='新会话' THEN ? ELSE title END,updated_at=? WHERE id=?",
                request.content.trim().substring(0, Math.min(60, request.content.trim().length())), now, id.toString());
        analysis.run(task);
        return Map.of("taskId", task.toString(), "assistantMessageId", assistantMessage.toString());
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable UUID id, Authentication authentication) { return taskForUser(id, authentication); }

    @GetMapping("/tasks/{id}/history") public List<Map<String,Object>> taskHistory(@PathVariable UUID id,Authentication authentication){taskForUser(id,authentication);return jdbc.queryForList("SELECT sequence_no,status,stage,message,occurred_at FROM analysis_task_event WHERE task_id=? ORDER BY sequence_no",id.toString());}

    @GetMapping(value = "/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id, Authentication authentication) { return events.subscribe(id, taskForUser(id, authentication)); }

    @PostMapping("/tasks/{id}/cancel")
    public void cancel(@PathVariable UUID id, Authentication authentication) {
        Map<String, Object> task = taskForUser(id, authentication);
        if (List.of("QUEUED", "RUNNING").contains(String.valueOf(task.get("status")))) analysis.cancel(id);
    }

    @GetMapping("/messages/{id}/export.csv") public ResponseEntity<byte[]> exportCsv(@PathVariable UUID id,Authentication authentication)throws Exception{Map<String,Object> message=messageForUser(id,authentication);List<Map<String,Object>> rows=json.readValue(String.valueOf(message.get("result_json")),List.class);StringBuilder csv=new StringBuilder("\uFEFF");if(!rows.isEmpty()){List<String> columns=new ArrayList<>(rows.getFirst().keySet());csv.append(String.join(",",columns.stream().map(ChatController::csv).toList())).append("\r\n");for(Map<String,Object> row:rows)csv.append(String.join(",",columns.stream().map(c->csv(value(row.get(c)))).toList())).append("\r\n");}return download(csv.toString().getBytes(StandardCharsets.UTF_8),"text/csv;charset=UTF-8","analysis-result.csv");}
    @GetMapping("/messages/{id}/export.md") public ResponseEntity<byte[]> exportMarkdown(@PathVariable UUID id,Authentication authentication){Map<String,Object> message=messageForUser(id,authentication);StringBuilder md=new StringBuilder("# 智能分析报告\n\n").append(message.get("content")).append("\n\n## 实际执行的查询\n\n```").append("DSL".equals(message.get("query_language"))?"json":"sql").append("\n").append(message.get("sql_text")).append("\n```\n");try{JsonNode evidence=json.readTree(Objects.toString(message.get("evidence_json"),"{}"));if(evidence.path("citations").isArray()&&!evidence.path("citations").isEmpty()){md.append("\n## 知识库引用\n");int i=1;for(JsonNode citation:evidence.path("citations")){md.append("\n").append(i++).append(". ").append(citation.path("document_name").asText());if(!citation.path("page_number").isMissingNode()&&!citation.path("page_number").isNull())md.append("，第 ").append(citation.path("page_number").asInt()).append(" 页");md.append("\n   > ").append(citation.path("content").asText().replace("\n"," ")).append("\n");}}}catch(Exception ignored){}md.append("\n> 报告基于当次实际查询和检索证据生成。\n");return download(md.toString().getBytes(StandardCharsets.UTF_8),"text/markdown;charset=UTF-8","analysis-report.md");}

    private Map<String, Object> taskForUser(UUID id, Authentication authentication) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id,t.status,t.stage,t.error_code,t.error_message,t.assistant_message_id,t.created_at,t.updated_at
                FROM analysis_task t JOIN chat_conversation c ON c.id=t.conversation_id
                WHERE t.id=? AND c.user_id=?
                """, id.toString(), actor(authentication).id().toString());
        if (rows.isEmpty()) throw new BusinessException(404, "TASK_NOT_FOUND", "任务不存在");
        return rows.getFirst();
    }
    private Map<String,Object> messageForUser(UUID id,Authentication authentication){List<Map<String,Object>> rows=jdbc.queryForList("SELECT m.content,m.sql_text,m.query_language,m.result_json,m.evidence_json FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.id=? AND m.role='ASSISTANT' AND m.status='COMPLETED' AND c.user_id=?",id.toString(),actor(authentication).id().toString());if(rows.isEmpty())throw new BusinessException(404,"MESSAGE_NOT_FOUND","可导出的回答不存在");return rows.getFirst();}
    private static ResponseEntity<byte[]> download(byte[] body,String type,String filename){return ResponseEntity.ok().header("Content-Type",type).header("Content-Disposition","attachment; filename=\""+filename+"\"").body(body);}
    private static String value(Object value){if(value==null)return "";if(value instanceof Map<?,?>||value instanceof List<?>)return value.toString();return String.valueOf(value);}
    private static String csv(String value){return "\""+value.replace("\"","\"\"")+"\"";}

    private void ownConversation(UUID id, Authentication authentication) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation WHERE id=? AND user_id=?", Integer.class, id.toString(), actor(authentication).id().toString());
        if (count == null || count == 0) throw new BusinessException(404, "CONVERSATION_NOT_FOUND", "会话不存在");
    }
    private void requireEnabled(String table, UUID id) {
        if (!Set.of("model_config", "data_source","knowledge_base").contains(table)) throw new IllegalArgumentException();
        String extra="knowledge_base".equals(table)?" AND active_generation_id IS NOT NULL":"";Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=? AND enabled=TRUE"+extra, Integer.class, id.toString());
        if (count == null || count == 0) throw new BusinessException(422, "CONFIG_NOT_AVAILABLE", "所选配置未启用");
    }
    private static PlatformPrincipal actor(Authentication authentication) { return (PlatformPrincipal) authentication.getPrincipal(); }

    public record ConversationRequest(String title, @NotNull UUID modelId, @NotNull UUID sourceId,List<UUID> knowledgeBaseIds) {}
    public record MessageRequest(@NotBlank @Size(max = 4000) String content) {}
    public record RenameRequest(@NotBlank @Size(max=160) String title){}
}
