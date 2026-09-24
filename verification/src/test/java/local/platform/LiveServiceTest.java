package local.platform;

import com.fasterxml.jackson.databind.*;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real-service smoke probes. Never creates/modifies existing business data. */
class LiveServiceTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final ExecutionConfig LIVE_ONCE=ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(120)).build();
    static String env(String name){
        String value=System.getenv(name);if(value!=null)return value;
        Path config=Path.of("..", ".local", "services.json");
        if(!Files.isRegularFile(config))return null;
        try{return JSON.readTree(config.toFile()).path(name).asText(null);}
        catch(Exception e){throw new IllegalStateException("Cannot read private service configuration");}
    }
    static String required(String name){String value=env(name);assumeTrue(value!=null&&!value.isBlank(),name+" is not configured");return value;}

    @Test void realOpenAiCompatibleModelCallsSyntheticReadOnlyTool() throws Exception {
        String url=required("PROBE_OPENAI_URL"), model=required("PROBE_OPENAI_MODEL"), key=required("PROBE_OPENAI_KEY");
        var tool=new ProviderProtocolTest.EvidenceTool();
        try(var agent=ProviderProtocolTest.agent(OpenAIChatModel.builder().baseUrl(url).modelName(model).apiKey(key).stream(true).build(),tool,LIVE_ONCE)) {
            var reply=agent.call(new UserMessage("Call lookup_total exactly once with region east. State the returned total and evidenceId. Do not guess."))
                    .block(Duration.ofSeconds(180));
            assertThat(tool.calls.get()).isEqualTo(1);
            assertThat(reply.getTextContent()).contains("42","fixture-e1");
            evidence("live-openai","tool-round-trip=PASS\n");
        }
    }
    @Test void realOllamaModelCallsSyntheticReadOnlyTool() throws Exception {
        String url=required("PROBE_OLLAMA_URL"),model=required("PROBE_OLLAMA_MODEL");
        var tool=new ProviderProtocolTest.EvidenceTool();
        try(var agent=ProviderProtocolTest.agent(OllamaChatModel.builder().baseUrl(url).modelName(model).stream(true).build(),tool,LIVE_ONCE)) {
            var reply=agent.call(new UserMessage("Call lookup_total exactly once with region east. State the returned total and evidenceId. Do not guess."))
                    .block(Duration.ofSeconds(180));
            assertThat(tool.calls.get()).isEqualTo(1);assertThat(reply.getTextContent()).contains("42","fixture-e1");
            evidence("live-ollama","tool-round-trip=PASS\n");
        }
    }
    @Test void realElasticsearchMappingAndBoundedReadOnlySearch() throws Exception {
        String base=required("PROBE_ES_URL").replaceAll("/+$",""),index=required("PROBE_ES_INDEX");
        assertThat(index).matches("[a-z0-9._-]+");
        try(HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            JsonNode root=es(client,base+"/",null);
            String indices=env("PROBE_ES_INDICES");
            for(String selected:(indices==null?index:indices).split(",")) {
                assertThat(selected).matches("[a-z0-9._-]+");
                JsonNode mapping=es(client,base+"/"+selected+"/_mapping",null);
                assertThat(mapping.size()).isEqualTo(1);
                JsonNode result=es(client,base+"/"+selected+"/_search","{\"size\":0,\"timeout\":\"5s\",\"track_total_hits\":10000,\"query\":{\"match_all\":{}}}");
                assertThat(result.path("timed_out").asBoolean()).isFalse();
                assertThat(result.path("_shards").path("failed").asInt()).isZero();
                assertThat(result.path("hits").path("hits").size()).isZero();
                assertThat(result.path("hits").path("total").isMissingNode()).isFalse();
                JsonNode empty=es(client,base+"/"+selected+"/_search","{\"size\":0,\"query\":{\"match_none\":{}}}");
                assertThat(empty.path("hits").path("total").path("value").asLong(-1)).isZero();
                JsonNode properties=mapping.elements().next().path("mappings").path("properties");
                String keyword=null,date=null;
                var fields=properties.fields();
                while(fields.hasNext()) {
                    var field=fields.next();String type=field.getValue().path("type").asText();
                    if(keyword==null && type.equals("keyword") && !field.getValue().path("doc_values").isBoolean())keyword=field.getKey();
                    if(keyword==null && field.getValue().path("fields").path("keyword").path("type").asText().equals("keyword")
                            && !field.getValue().path("fields").path("keyword").path("doc_values").isBoolean())keyword=field.getKey()+".keyword";
                    if(date==null && type.equals("date"))date=field.getKey();
                }
                if(keyword!=null) {
                    var aggregate=JSON.createObjectNode();aggregate.put("size",0).put("timeout","5s");
                    aggregate.putObject("aggs").putObject("probe_terms").putObject("terms").put("field",keyword).put("size",3);
                    JsonNode buckets=es(client,base+"/"+selected+"/_search",aggregate.toString());
                    assertThat(buckets.path("timed_out").asBoolean()).isFalse();
                    assertThat(buckets.path("_shards").path("failed").asInt()).isZero();
                    assertThat(buckets.path("aggregations").path("probe_terms").path("buckets").size()).isLessThanOrEqualTo(3);
                    assertThat(buckets.path("aggregations").path("probe_terms").has("sum_other_doc_count")).isTrue();
                    evidence("live-es-aggregation-"+selected,"terms-size-3=PASS\ntruncation-metadata-present=PASS\n");
                }
                if(date!=null) {
                    var filtered=JSON.createObjectNode();filtered.put("size",0).put("timeout","5s");
                    filtered.putObject("query").putObject("range").putObject(date).put("gte","2026-09-14").put("lt","2026-09-22").put("format","yyyy-MM-dd").put("time_zone","+08:00");
                    JsonNode range=es(client,base+"/"+selected+"/_search",filtered.toString());
                    assertThat(range.path("timed_out").asBoolean()).isFalse();
                    assertThat(range.path("_shards").path("failed").asInt()).isZero();
                    evidence("live-es-date-"+selected,"bounded-date-filter=PASS\n");
                }
            }
            evidence("live-elasticsearch","version="+root.path("version").path("number").asText()+"\nmapping=PASS\nbounded-search=PASS\nindices-checked="+(indices==null?1:indices.split(",").length)+"\n");
        }
    }
    static JsonNode es(HttpClient client,String url,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(env("PROBE_ES_API_KEY")!=null) request.header("Authorization","ApiKey "+env("PROBE_ES_API_KEY"));
        else if(env("PROBE_ES_USER")!=null) request.header("Authorization","Basic "+Base64.getEncoder().encodeToString((env("PROBE_ES_USER")+":"+required("PROBE_ES_PASSWORD")).getBytes(StandardCharsets.UTF_8)));
        if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(body));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Elasticsearch HTTP status (body omitted)").isEqualTo(200);
        return JSON.readTree(response.body());
    }
    @Test void realMysqlConnectionAndReadOnlyTransaction()throws Exception {
        String url=required("PROBE_MYSQL_JDBC"),user=required("PROBE_MYSQL_USER"),password=required("PROBE_MYSQL_PASSWORD");
        assertThat(url).startsWith("jdbc:mysql:");
        try(Connection connection=DriverManager.getConnection(url,user,password)) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                try(var result=statement.executeQuery("SELECT VERSION(), 1 + 1")) {
                    assertThat(result.next()).isTrue();assertThat(result.getInt(2)).isEqualTo(2);
                    evidence("live-mysql","version="+result.getString(1)+"\nread-only-select=PASS\n");
                }
                boolean writePrivileges=false;
                try(var result=statement.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                    while(result.next()) {
                        String grant=result.getString(1).toUpperCase(Locale.ROOT);
                        if(grant.matches(".*\\b(ALL PRIVILEGES|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|FILE|EXECUTE|SUPER)\\b.*"))writePrivileges=true;
                    }
                }
                evidence("live-mysql-privileges","write-or-admin-privileges-present="+writePrivileges+"\n");
            }finally{connection.rollback();}
        }
    }
    @Test void realPostgresVectorExtensionAndScopedExactSearch()throws Exception {
        String url=required("PROBE_PG_JDBC"),user=required("PROBE_PG_USER"),password=required("PROBE_PG_PASSWORD");
        assertThat(url).startsWith("jdbc:postgresql:");
        try(Connection connection=DriverManager.getConnection(url,user,password)) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                try(var result=statement.executeQuery("SELECT version()")) {
                    assertThat(result.next()).isTrue();evidence("live-postgres-server","version="+result.getString(1)+"\n");
                }
                StringBuilder extensionStatus=new StringBuilder();
                try(var result=statement.executeQuery("SELECT name, default_version, installed_version FROM pg_available_extensions WHERE name IN ('vector','kvector')")) {
                    while(result.next())extensionStatus.append(result.getString(1)).append(": available=").append(result.getString(2)).append(", installed=").append(result.getString(3)).append('\n');
                }
                evidence("live-vector-availability",extensionStatus.isEmpty()?"vector/kvector not listed among available extensions\n":extensionStatus.toString());
                try(var result=statement.executeQuery("SELECT extversion FROM pg_extension WHERE extname='vector'")) {
                    assertThat(result.next()).as("pgvector must already be installed by its owner").isTrue();
                    evidence("live-postgres","pgvector="+result.getString(1)+"\n");
                }
                try(var result=statement.executeQuery("WITH fixture(generation, label, embedding) AS (VALUES (1, 'relevant', '[1,0,0]'::vector), (1, 'other', '[0,1,0]'::vector), (2, 'excluded', '[1,0,0]'::vector)) SELECT label FROM fixture WHERE generation=1 ORDER BY embedding <=> '[1,0,0]'::vector LIMIT 1")) {
                    assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("relevant");
                }
            }finally{connection.rollback();}
        }
    }
    static void evidence(String name,String content)throws Exception {
        Path output=Path.of("target","evidence");Files.createDirectories(output);
        Files.writeString(output.resolve(name+".txt"),content,StandardCharsets.UTF_8);
    }
}
