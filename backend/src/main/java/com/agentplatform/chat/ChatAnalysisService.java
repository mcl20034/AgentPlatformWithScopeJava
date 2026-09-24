package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import com.agentplatform.config.SecretService;
import com.agentplatform.knowledge.KnowledgeRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import io.micrometer.core.instrument.MeterRegistry;
import com.agentplatform.operations.OperationsProperties;

@Service
class ChatAnalysisService {
    private static final Logger log=LoggerFactory.getLogger(ChatAnalysisService.class);
    private final JdbcTemplate jdbc; private final SecretService secrets; private final ObjectMapper json; private final ChatTaskEvents events;private final KnowledgeRetriever knowledge;private final MeterRegistry metrics;private final OperationsProperties operations;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<UUID,Thread> workers = new ConcurrentHashMap<>();
    private final Map<UUID,Statement> activeStatements = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(4);
    private final ThreadLocal<UUID> currentTask = new ThreadLocal<>();

    ChatAnalysisService(JdbcTemplate jdbc, SecretService secrets, ObjectMapper json, ChatTaskEvents events,KnowledgeRetriever knowledge,MeterRegistry metrics,OperationsProperties operations) {
        this.jdbc=jdbc;this.secrets=secrets;this.json=json;this.events=events;this.knowledge=knowledge;this.metrics=metrics;this.operations=operations;
    }

    @Async("chatTaskExecutor") public void run(UUID taskId) {
        long taskStart=System.nanoTime();boolean acquired=false;
        try {
            currentTask.set(taskId);
            workers.put(taskId,Thread.currentThread());
            acquired=capacity.tryAcquire();if(!acquired)throw new BusinessException(429,"ANALYSIS_CAPACITY_FULL","当前分析任务较多，请稍后重试");
            jdbc.update("UPDATE analysis_task SET started_at=?,updated_at=? WHERE id=?",com.agentplatform.common.DatabaseTime.now(),com.agentplatform.common.DatabaseTime.now(),taskId.toString());
            Config c=config(taskId);stage(taskId,"UNDERSTANDING","正在理解问题、会话上下文与授权范围");checkCancelled(taskId);
            String clarification=clarification(c);if(clarification!=null){long duration=elapsed(taskStart);jdbc.update("UPDATE chat_message SET content=?,evidence_json=?,duration_ms=?,status='COMPLETED' WHERE id=?",clarification,"{\"clarification\":true}",duration,c.assistantMessageId);jdbc.update("UPDATE analysis_task SET clarification_requested=TRUE WHERE id=?",taskId.toString());completeEvaluation(taskId,clarification,"");finish(taskId,"COMPLETED","CLARIFICATION",null,"等待用户补充分析条件");return;}
            stage(taskId,"GENERATING_QUERY","正在生成受控查询");String query=generateQuery(taskId,c);checkCancelled(taskId);
            stage(taskId,"QUERYING","正在执行只读查询并收集证据");Execution execution;try{execution="MYSQL".equals(c.sourceType)?queryMysql(taskId,c,query):queryElasticsearch(c,query);}catch(BusinessException first){if(!Set.of("MYSQL_QUERY_FAILED","ELASTICSEARCH_QUERY_FAILED").contains(first.code()))throw first;stage(taskId,"REPAIRING_QUERY","查询执行失败，正在根据错误信息修正一次");query=repairQuery(taskId,c,query,first);jdbc.update("UPDATE analysis_task SET retry_count=retry_count+1 WHERE id=?",taskId.toString());execution="MYSQL".equals(c.sourceType)?queryMysql(taskId,c,query):queryElasticsearch(c,query);}checkCancelled(taskId);
            List<Map<String,Object>> citations=List.of();if(!c.knowledgeBaseIds.isEmpty()){stage(taskId,"RETRIEVING_KNOWLEDGE","正在检索知识库并校验引用");try{citations=knowledge.retrieve(c.knowledgeBaseIds,c.question,6);execution.evidence.put("citations",citations);execution.evidence.put("knowledgeStatus","COMPLETED");}catch(BusinessException e){execution.evidence.put("knowledgeStatus","FAILED");execution.evidence.put("knowledgeError",e.code());execution.evidence.put("partialFailure",true);log.warn("知识库检索失败，继续使用结构化数据完成回答 taskId={} code={}",taskId,e.code());}}checkCancelled(taskId);
            stage(taskId,"GENERATING_ANSWER","正在根据真实查询结果生成结论");String answer=generateAnswer(taskId,c,query,execution.rows,citations);long duration=elapsed(taskStart);
            jdbc.update("UPDATE chat_message SET content=?,sql_text=?,query_language=?,result_json=?,evidence_json=?,duration_ms=?,status='COMPLETED' WHERE id=?",
                    answer,query,"MYSQL".equals(c.sourceType)?"SQL":"DSL",json.writeValueAsString(execution.rows),json.writeValueAsString(execution.evidence),duration,c.assistantMessageId);
            completeEvaluation(taskId,answer,query);
            finish(taskId,"COMPLETED","COMPLETED",null,"分析完成");
        } catch(CancelledException ignored){finish(taskId,"CANCELLED","CANCELLED","TASK_CANCELLED","任务已停止");}
        catch(BusinessException e){fail(taskId,e.code(),e.getMessage());}
        catch(Exception e){if(cancelled.contains(taskId))finish(taskId,"CANCELLED","CANCELLED","TASK_CANCELLED","任务已停止");else fail(taskId,"ANALYSIS_FAILED",safe(e));}
        finally{try{String status=jdbc.queryForObject("SELECT status FROM analysis_task WHERE id=?",String.class,taskId.toString());metrics.counter("agent.analysis.tasks","status",status==null?"UNKNOWN":status).increment();}catch(Exception ignored){}currentTask.remove();if(acquired)capacity.release();activeStatements.remove(taskId);workers.remove(taskId);cancelled.remove(taskId);}
    }
    void cancel(UUID taskId){cancelled.add(taskId);Statement statement=activeStatements.get(taskId);if(statement!=null)try{statement.cancel();}catch(Exception ignored){}Thread worker=workers.get(taskId);if(worker!=null)worker.interrupt();}

    private String generateQuery(UUID taskId,Config c)throws Exception{
        String constraints="MYSQL".equals(c.sourceType)?"只输出一条 MySQL SELECT，可使用 CTE 和子查询，不要 Markdown。":"只输出 Elasticsearch _search 请求体 JSON，不要 Markdown；禁止 script、runtime_mappings、跨索引和深分页。";
        if("MYSQL".equals(c.sourceType))return SqlGuard.validate(callModel(c,"你是数据分析查询生成器。"+constraints+context(c)+"\n数据库结构："+limit(c.schemaJson,30000)+"\n授权范围："+c.allowedObjects+"\n当前用户问题："+c.question),allowedValues(c.allowedObjects,"tables"));
        Set<String> fields=allowedValues(c.allowedObjects,"fields");
        JsonNode semantics=fieldSemantics(c.allowedObjects);
        String prompt=elasticsearchPlanPrompt(c,fields,semantics);
        String candidate=callModel(c,prompt);
        try{return validateElasticsearchPlan(candidate,fields,semantics);}
        catch(BusinessException first){
            if(!Set.of("DSL_REJECTED","ES_PLAN_REJECTED").contains(first.code()))throw first;
            String correction=prompt+"\n你上一次生成的查询计划未通过校验："+first.getMessage()
                    +"\n上一次输出："+limit(candidate,8000)
                    +"\n请重新输出简短且完整的查询计划 JSON，必须闭合所有大括号，只能使用语义目录中的真实字段。";
            return validateElasticsearchPlan(callModel(c,correction),fields,semantics);
        }
    }
    private String validateElasticsearchPlan(String candidate,Set<String> fields,JsonNode semantics){
        String dsl=ElasticsearchQueryPlanner.build(candidate,fields,semantics,json);
        return ElasticsearchDslGuard.validate(dsl,fields,json);
    }
    private String repairQuery(UUID taskId,Config c,String previous,BusinessException failure)throws Exception{
        String prompt="你是查询纠错器。根据数据库结构、授权范围和执行错误修正查询，只允许修改一次。"
                +("MYSQL".equals(c.sourceType)?"只输出一条 MySQL SELECT，不要 Markdown。":"只输出查询计划 JSON，不要直接输出 DSL 或 Markdown。")
                +context(c)+"\n当前问题："+c.question+"\n上一次查询："+limit(previous,12000)+"\n执行错误："+limit(failure.getMessage(),1000)+"\n结构："+limit(c.schemaJson,20000)+"\n授权："+c.allowedObjects;
        String candidate=callModel(c,prompt);
        if("MYSQL".equals(c.sourceType))return SqlGuard.validate(candidate,allowedValues(c.allowedObjects,"tables"));
        return validateElasticsearchPlan(candidate,allowedValues(c.allowedObjects,"fields"),fieldSemantics(c.allowedObjects));
    }
    private String clarification(Config c){
        if(!c.context.isBlank())return null;String q=c.question.trim().replaceAll("[？?。！!]$","");
        if(q.length()<=2||Set.of("分析一下","查一下","看一下","看看","继续","这个呢","再分析","为什么").contains(q))return "为了生成准确且可核查的查询，请补充要分析的指标、时间范围，以及需要的筛选或分组维度。例如：统计最近 7 天各站点的新闻发布数量。";
        return null;
    }
    private String context(Config c){return c.context.isBlank()?"":"\n最近会话上下文（用于理解代词和追问，不得作为事实证据）：\n"+c.context;}
    private JsonNode fieldSemantics(String allowedObjects)throws Exception{return json.readTree(allowedObjects).path("fieldSemantics");}
    private String elasticsearchPlanPrompt(Config c,Set<String> fields,JsonNode semantics){
        return "你是 Elasticsearch 查询规划器。只输出查询计划 JSON，不要输出 Elasticsearch DSL、Markdown 或解释。"
                +"\n查询计划格式：{\"filters\":[{\"field\":\"真实字段名\",\"operator\":\"term|terms|range|match|match_phrase\",\"value\":\"值\",\"gte\":\"下界\",\"lte\":\"上界\"}],\"timeRange\":{\"field\":\"日期字段\",\"gte\":\"now-7d/d\",\"lte\":\"now\",\"timeZone\":\"Asia/Shanghai\"},\"groupBy\":[{\"field\":\"分组字段\",\"size\":20}],\"dateHistogram\":{\"field\":\"日期字段\",\"calendarInterval\":\"day\"},\"sort\":[{\"field\":\"排序字段\",\"order\":\"desc\"}],\"sourceFields\":[\"返回字段\"],\"size\":20}"
                +"\n没有对应条件时省略属性。按某字段统计时使用 groupBy；按时间趋势统计时使用 dateHistogram；只做聚合时可省略 size。"
                +"\n授权字段："+json.valueToTree(fields)
                +"\n字段语义目录："+semantics
                +"\n技术结构："+limit(c.schemaJson,20000)
                +context(c)+"\n当前用户问题："+c.question;
    }
    private String validateElasticsearchCandidate(Config c,String candidate,Set<String> fields)throws Exception{
        try{return ElasticsearchDslGuard.validate(repairFieldPlaceholders(c,candidate,fields),fields,json);}
        catch(BusinessException exception){throw exception;}
        catch(Exception exception){throw new BusinessException(422,"DSL_REJECTED","模型返回的 Elasticsearch DSL JSON 不完整或格式错误");}
    }
    private String elasticsearchPrompt(Config c,String constraints,Set<String> fields){
        return "你是 Elasticsearch 查询生成器。"+constraints
                +"\n唯一允许访问的字段（必须逐字选用，field、your_field 等只是占位符，严禁输出）："+json.valueToTree(fields)
                +"\n聚合、排序、_source、exists 和检索条件引用的字段也必须来自此列表；文本字段聚合可在 mapping 确实存在时使用 .keyword 子字段。"
                +"\n索引 mapping："+limit(c.schemaJson,30000)
                +"\n用户问题："+c.question;
    }
    private String repairFieldPlaceholders(Config c,String raw,Set<String> fields)throws Exception{
        String value=raw.trim().replaceFirst("(?s)^```(?:json)?\\s*","").replaceFirst("(?s)\\s*```$","");
        int first=value.indexOf('{'),last=value.lastIndexOf('}');if(first<0||last<first)return raw;
        JsonNode parsed=json.readTree(value.substring(first,last+1));if(!parsed.isObject())return raw;
        List<FieldPlaceholder> placeholders=new ArrayList<>();collectFieldPlaceholders(parsed,null,placeholders);
        for(FieldPlaceholder placeholder:placeholders){
            String selected=selectField(c,fields,placeholder.object);
            if(placeholder.kind==PlaceholderKind.CLAUSE_KEY){JsonNode content=placeholder.object.remove("field");placeholder.object.set(selected,content);}
            else if(placeholder.kind==PlaceholderKind.SORT_FIELD){ObjectNode options=placeholder.object.deepCopy();placeholder.object.removeAll();placeholder.object.set(selected,options);}
            else placeholder.object.put("field",selected);
        }
        return placeholders.isEmpty()?raw:json.writeValueAsString(parsed);
    }
    private String selectField(Config c,Set<String> fields,ObjectNode clause)throws Exception{
        String answer=callModel(c,"你是字段选择器。只能从候选字段中选择最符合用户问题和当前 DSL 子句的一个字段。只输出字段名，不要解释，不要输出 JSON。"
                +"聚合和排序优先选择 mapping 中可聚合的 keyword、日期或数值字段；text 字段只有在 mapping 确实提供 keyword 子字段时才可输出 .keyword。"
                +"\n候选字段："+json.valueToTree(fields)+"\n索引 mapping："+limit(c.schemaJson,20000)+"\n用户问题："+c.question+"\n当前 DSL 子句："+clause);
        return selectedField(answer,fields);
    }
    private void collectFieldPlaceholders(JsonNode node,String parentKey,List<FieldPlaceholder> result){
        if(node.isArray()){node.forEach(child->collectFieldPlaceholders(child,parentKey,result));return;}
        if(!node.isObject())return;
        ObjectNode object=(ObjectNode)node;JsonNode field=object.get("field");
        if(field!=null&&Set.of("term","terms","range","match","match_phrase","prefix","wildcard","regexp").contains(parentKey))result.add(new FieldPlaceholder(object,PlaceholderKind.CLAUSE_KEY));
        else if(field!=null&&field.isTextual()&&isFieldPlaceholder(field.asText()))result.add(new FieldPlaceholder(object,PlaceholderKind.FIELD_VALUE));
        else if("sort".equals(parentKey)&&field==null&&object.size()>0&&object.size()<=5&&streamFieldNames(object).allMatch(Set.of("order","mode","missing","unmapped_type","numeric_type")::contains))result.add(new FieldPlaceholder(object,PlaceholderKind.SORT_FIELD));
        object.fields().forEachRemaining(entry->collectFieldPlaceholders(entry.getValue(),entry.getKey(),result));
    }
    private java.util.stream.Stream<String> streamFieldNames(ObjectNode object){List<String> names=new ArrayList<>();object.fieldNames().forEachRemaining(names::add);return names.stream();}
    private boolean isFieldPlaceholder(String value){return Set.of("field","your_field","field_name","your_field_name").contains(value.trim().toLowerCase(Locale.ROOT));}
    private String selectedField(String answer,Set<String> fields){
        String value=answer.trim().replace("`","").replace("\"","");
        for(String field:fields)if(value.equals(field)||value.equals(field+".keyword"))return value;
        List<String> longestFirst=fields.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for(String field:longestFirst)if(value.contains(field+".keyword"))return field+".keyword";
        for(String field:longestFirst)if(value.contains(field))return field;
        throw new BusinessException(422,"DSL_FIELD_SELECTION_FAILED","模型未能从授权范围中选择有效字段");
    }
    private String generateAnswer(UUID taskId,Config c,String query,List<Map<String,Object>> rows,List<Map<String,Object>> citations)throws Exception{
        StringBuilder sources=new StringBuilder();for(int i=0;i<citations.size();i++){Map<String,Object> item=citations.get(i);sources.append("\n[").append(i+1).append("] ").append(item.get("document_name"));if(item.get("page_number")!=null)sources.append(" 第").append(item.get("page_number")).append("页");sources.append("：").append(item.get("content"));}
        try{return sanitizeCitations(callModel(c,"你是数据分析助手。依据真实查询结果和知识库片段用简体中文回答，不得编造。结合会话上下文理解追问，但最终结论只能由本次真实结果支持。先给出核心结论，再说明口径、关键数字和限制。引用知识库时必须使用 [1] 形式，且只能引用提供的编号；结果为空时明确说明。"+context(c)+"\n当前用户问题："+c.question+"\n实际执行的"+("MYSQL".equals(c.sourceType)?"SQL":"DSL")+"："+query+"\n查询结果："+limit(json.writeValueAsString(rows),24000)+"\n知识库证据："+limit(sources.toString(),16000)),citations.size());}
        catch(BusinessException exception){
            if(!Set.of("MODEL_TIMEOUT","MODEL_CALL_FAILED","MODEL_EMPTY_RESPONSE").contains(exception.code()))throw exception;
            log.warn("模型结论生成失败，任务降级完成 taskId={} code={} message={}",taskId,exception.code(),exception.getMessage());
            stage(taskId,"ANSWER_FALLBACK","模型生成结论超时，正在保留查询结果并生成基础说明");
            return fallbackAnswer(rows,exception.code());
        }
    }
    private String fallbackAnswer(List<Map<String,Object>> rows,String reason){
        if(rows.isEmpty())return "查询已经执行完成，但没有找到符合条件的数据。模型生成详细结论时超时，您仍可查看已执行的查询和证据。";
        if(rows.size()==1&&rows.getFirst().containsKey("aggregations"))return "查询已经执行完成并返回聚合统计结果。模型生成详细结论时超时，请查看下方结构化结果、图表和查询证据。";
        return "查询已经执行完成，共返回 "+rows.size()+" 条结果。模型生成详细结论时超时，请查看下方结构化结果和查询证据。";
    }
    private String sanitizeCitations(String answer,int count){java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(answer);StringBuffer result=new StringBuffer();while(matcher.find()){int value=Integer.parseInt(matcher.group(1));matcher.appendReplacement(result,value>=1&&value<=count?matcher.group():"");}matcher.appendTail(result);return result.toString();}
    private String callModel(Config c,String prompt)throws Exception{
        long callStart=System.nanoTime();String callStatus="FAILED",errorCode=null;JsonNode root=null;
        try{
        boolean ollama="OLLAMA".equals(c.provider);URI uri=URI.create(c.modelEndpoint.replaceAll("/$","")+(ollama?"/api/chat":"/chat/completions"));
        Map<String,Object> message=Map.of("role","user","content",prompt);Map<String,Object> body=ollama
                ?Map.of("model",c.modelName,"messages",List.of(message),"stream",false,"options",Map.of("num_predict",2048))
                :Map.of("model",c.modelName,"messages",List.of(message),"temperature",0,"max_tokens",2048);
        HttpRequest.Builder request=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(c.timeoutMs)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        String key=c.modelSecretId==null?null:secrets.reveal(UUID.fromString(c.modelSecretId),"MODEL");if(key!=null&&!key.isBlank())request.header("Authorization","Bearer "+key);
        HttpResponse<String> response;try{response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());}catch(HttpTimeoutException exception){log.warn("模型调用超时 provider={} model={} timeoutMs={}",c.provider,c.modelName,c.timeoutMs);throw new BusinessException(504,"MODEL_TIMEOUT","模型调用超时（"+c.timeoutMs+" 毫秒）");}if(response.statusCode()/100!=2)throw new BusinessException(502,"MODEL_CALL_FAILED","模型调用失败，HTTP "+response.statusCode());
        root=json.readTree(response.body());String content=ollama?root.path("message").path("content").asText():root.path("choices").path(0).path("message").path("content").asText();if(content.isBlank())throw new BusinessException(502,"MODEL_EMPTY_RESPONSE","模型未返回有效内容");
        String result=content.trim();callStatus="SUCCESS";if(operations.logModelContent())log.info("模型调用结果 provider={} model={} chars={} content={}",c.provider,c.modelName,result.length(),limit(result,20000));else log.info("模型调用完成 provider={} model={} chars={}",c.provider,c.modelName,result.length());return result;
        }catch(BusinessException e){errorCode=e.code();throw e;}catch(Exception e){errorCode="MODEL_CALL_EXCEPTION";throw e;}finally{
            long duration=elapsed(callStart);long promptTokens=root==null?0:root.path("usage").path("prompt_tokens").asLong(root.path("prompt_eval_count").asLong());long completionTokens=root==null?0:root.path("usage").path("completion_tokens").asLong(root.path("eval_count").asLong());long total=root==null?promptTokens+completionTokens:root.path("usage").path("total_tokens").asLong(promptTokens+completionTokens);
            String purpose=prompt.startsWith("你是数据分析助手")?"ANSWER_GENERATION":prompt.startsWith("你是字段选择器")?"FIELD_SELECTION":prompt.contains("上一次生成")?"QUERY_REPAIR":"QUERY_GENERATION";
            try{jdbc.update("INSERT INTO model_call_log(id,task_id,provider,model_name,purpose,status,duration_ms,prompt_tokens,completion_tokens,total_tokens,error_code,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),currentTask.get()==null?null:currentTask.get().toString(),c.provider,c.modelName,purpose,callStatus,duration,promptTokens,completionTokens,total,errorCode,com.agentplatform.common.DatabaseTime.now());}catch(Exception e){log.warn("模型调用审计写入失败",e);}
            metrics.counter("agent.model.calls","provider",c.provider,"status",callStatus).increment();metrics.timer("agent.model.call.duration","provider",c.provider).record(Duration.ofMillis(duration));
        }
    }
    private Execution queryMysql(UUID taskId,Config c,String sql)throws Exception{
        long start=System.nanoTime();Properties p=new Properties();p.setProperty("user",Objects.toString(c.sourceUsername,""));p.setProperty("password",sourcePassword(c));p.setProperty("connectTimeout","5000");p.setProperty("socketTimeout","15000");
        try(var connection=DriverManager.getConnection(c.sourceEndpoint,p);var statement=connection.createStatement()){activeStatements.put(taskId,statement);connection.setReadOnly(true);statement.setQueryTimeout(15);statement.setMaxRows(200);try(var rs=statement.executeQuery(sql)){ResultSetMetaData md=rs.getMetaData();List<Map<String,Object>> rows=new ArrayList<>();while(rs.next()&&rows.size()<200){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=md.getColumnCount();i++)row.put(md.getColumnLabel(i),rs.getObject(i));rows.add(row);}Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("sourceType","MYSQL");evidence.put("sourceName",c.sourceName);evidence.put("returnedRows",rows.size());evidence.put("truncated",rows.size()>=200);evidence.put("executionMs",elapsed(start));return new Execution(rows,evidence);}finally{activeStatements.remove(taskId);}}catch(java.sql.SQLException e){throw new BusinessException(422,"MYSQL_QUERY_FAILED","MySQL 查询执行失败："+safe(e));}
    }
    private Execution queryElasticsearch(Config c,String dsl)throws Exception{
        URI target=URI.create(c.sourceEndpoint.replaceAll("/$","")+"/"+c.indexName+"/_search");HttpRequest.Builder request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(dsl));
        if(c.sourceUsername!=null&&!c.sourceUsername.isBlank())request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((c.sourceUsername+":"+sourcePassword(c)).getBytes(StandardCharsets.UTF_8)));
        long start=System.nanoTime();HttpResponse<String> response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new BusinessException(502,"ELASTICSEARCH_QUERY_FAILED","Elasticsearch 查询失败，HTTP "+response.statusCode());JsonNode root=json.readTree(response.body());List<Map<String,Object>> rows=new ArrayList<>();
        for(JsonNode hit:root.path("hits").path("hits")){Map<String,Object> row=new LinkedHashMap<>();row.put("_id",hit.path("_id").asText());if(!hit.path("_score").isNull())row.put("_score",hit.path("_score").numberValue());if(hit.path("_source").isObject())row.putAll(json.convertValue(hit.path("_source"),Map.class));rows.add(row);}
        if(rows.isEmpty()&&root.has("aggregations"))rows.add(Map.of("aggregations",json.convertValue(root.path("aggregations"),Map.class)));
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("sourceType","ELASTICSEARCH");evidence.put("sourceName",c.sourceName);evidence.put("index",c.indexName);evidence.put("totalHits",root.path("hits").path("total").path("value").asLong(rows.size()));evidence.put("returnedRows",rows.size());evidence.put("tookMs",root.path("took").asLong(elapsed(start)));evidence.put("timedOut",root.path("timed_out").asBoolean(false));return new Execution(rows,evidence);
    }
    private Config config(UUID taskId){Config base=jdbc.query("""
      SELECT t.assistant_message_id,um.content,mv.provider,mv.endpoint,mv.model_name,mv.secret_version_id,mv.timeout_ms,
             ds.name,ds.type,dsv.endpoint,dsv.index_name,dsv.username,dsv.secret_version_id,dsv.schema_json,dsv.allowed_objects_json
      FROM analysis_task t JOIN chat_message um ON um.id=t.user_message_id JOIN chat_conversation c ON c.id=t.conversation_id
      JOIN model_config mc ON mc.id=c.model_id AND mc.enabled=TRUE JOIN model_version mv ON mv.id=mc.active_version_id AND mv.test_status='PASSED'
      JOIN data_source ds ON ds.id=c.source_id AND ds.enabled=TRUE JOIN data_source_version dsv ON dsv.id=ds.active_version_id AND dsv.test_status='PASSED'
      WHERE t.id=?
      """,rs->{if(!rs.next())throw new BusinessException(422,"CHAT_CONFIGURATION_UNAVAILABLE","模型或数据源当前不可用");return new Config(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),"",List.of());},taskId.toString());List<String> kbIds=jdbc.queryForList("SELECT ck.kb_id FROM chat_conversation_knowledge ck JOIN analysis_task t ON t.conversation_id=ck.conversation_id WHERE t.id=?",String.class,taskId.toString());List<Map<String,Object>> history=jdbc.queryForList("SELECT role,content FROM chat_message WHERE conversation_id=(SELECT conversation_id FROM analysis_task WHERE id=?) AND id NOT IN ((SELECT user_message_id FROM analysis_task WHERE id=?),(SELECT assistant_message_id FROM analysis_task WHERE id=?)) AND status='COMPLETED' ORDER BY created_at DESC LIMIT 8",taskId.toString(),taskId.toString(),taskId.toString());Collections.reverse(history);StringBuilder context=new StringBuilder();for(Map<String,Object> item:history)context.append("\n").append("USER".equals(String.valueOf(item.get("role")))?"用户":"助手").append("：").append(limit(String.valueOf(item.get("content")),1200));return new Config(base.assistantMessageId,base.question,base.provider,base.modelEndpoint,base.modelName,base.modelSecretId,base.timeoutMs,base.sourceName,base.sourceType,base.sourceEndpoint,base.indexName,base.sourceUsername,base.sourceSecretId,base.schemaJson,base.allowedObjects,context.toString(),kbIds);}
    private Set<String> allowedValues(String value,String key)throws Exception{Set<String> result=new LinkedHashSet<>();for(JsonNode item:json.readTree(value).path(key))result.add(item.asText());if(result.isEmpty())throw new BusinessException(422,"SOURCE_SCOPE_EMPTY","数据源没有已授权的查询范围");return result;}
    private String sourcePassword(Config c){return c.sourceSecretId==null?"":Objects.toString(secrets.reveal(UUID.fromString(c.sourceSecretId),"DATA_SOURCE"),"");}
    private void stage(UUID id,String stage,String message){jdbc.update("UPDATE analysis_task SET status='RUNNING',stage=?,updated_at=? WHERE id=?",stage,com.agentplatform.common.DatabaseTime.now(),id.toString());publish(id,"RUNNING",stage,message);}
    private void fail(UUID id,String code,String message){jdbc.update("UPDATE chat_message SET content=?,status='FAILED' WHERE id=(SELECT assistant_message_id FROM analysis_task WHERE id=?)",message,id.toString());finish(id,"FAILED","FAILED",code,message);}
    private void finish(UUID id,String status,String stage,String code,String message){var now=com.agentplatform.common.DatabaseTime.now();jdbc.update("UPDATE analysis_task SET status=?,stage=?,error_code=?,error_message=?,completed_at=?,updated_at=? WHERE id=?",status,stage,code,message,now,now,id.toString());if(!"COMPLETED".equals(status))jdbc.update("UPDATE evaluation_run SET status=?,completed_at=? WHERE task_id=?",status,now,id.toString());publish(id,status,stage,message);}
    private void completeEvaluation(UUID taskId,String answer,String query){try{List<Map<String,Object>> cases=jdbc.queryForList("SELECT c.expected_terms_json FROM evaluation_case c JOIN analysis_task t ON t.evaluation_case_id=c.id WHERE t.id=?",taskId.toString());if(cases.isEmpty())return;List<String> terms=json.readValue(String.valueOf(cases.getFirst().get("expected_terms_json")),json.getTypeFactory().constructCollectionType(List.class,String.class));String value=(answer+"\n"+query).toLowerCase(Locale.ROOT);List<String> matched=terms.stream().filter(term->value.contains(term.toLowerCase(Locale.ROOT))).toList();double score=terms.isEmpty()?0:(double)matched.size()/terms.size();jdbc.update("UPDATE evaluation_run SET status='COMPLETED',score=?,matched_terms_json=?,completed_at=? WHERE task_id=?",score,json.writeValueAsString(matched),com.agentplatform.common.DatabaseTime.now(),taskId.toString());}catch(Exception e){log.warn("评测结果计算失败 taskId={}",taskId,e);}}
    private void publish(UUID id,String status,String stage,String message){Integer seq=jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM analysis_task_event WHERE task_id=?",Integer.class,id.toString());jdbc.update("INSERT INTO analysis_task_event(id,task_id,sequence_no,status,stage,message,occurred_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id.toString(),seq,status,stage,message,com.agentplatform.common.DatabaseTime.now());events.publish(id,Map.of("taskId",id.toString(),"sequence",seq,"status",status,"stage",stage,"message",message));}
    private void checkCancelled(UUID id){if(cancelled.contains(id))throw new CancelledException();}
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
    private static String limit(String value,int max){return value.length()<=max?value:value.substring(0,max);}
    private static String safe(Exception e){String value=e.getMessage();return value==null?e.getClass().getSimpleName():value.replaceAll("(?i)(password|token|key)=[^&\\s]+","$1=***");}
    private record Execution(List<Map<String,Object>> rows,Map<String,Object> evidence){}
    private record FieldPlaceholder(ObjectNode object,PlaceholderKind kind){}
    private enum PlaceholderKind{CLAUSE_KEY,FIELD_VALUE,SORT_FIELD}
    private record Config(String assistantMessageId,String question,String provider,String modelEndpoint,String modelName,String modelSecretId,int timeoutMs,String sourceName,String sourceType,String sourceEndpoint,String indexName,String sourceUsername,String sourceSecretId,String schemaJson,String allowedObjects,String context,List<String> knowledgeBaseIds){}
    private static final class CancelledException extends RuntimeException{}
}
