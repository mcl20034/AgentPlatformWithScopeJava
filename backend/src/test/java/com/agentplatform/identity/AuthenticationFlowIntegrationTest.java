package com.agentplatform.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.agentplatform.knowledge.KnowledgeDocumentController;
import org.springframework.mock.web.MockMultipartFile;
import com.sun.net.httpserver.HttpServer;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationFlowIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeDocumentController documents;

    @Test
    void administratorCreatesUserAndUserMustChangeTemporaryPassword() throws Exception {
        Client admin = new Client();
        assertThat(admin.login("admin", "Initial-Admin-2026!").get("mustChangePassword").asBoolean()).isTrue();
        assertThat(admin.get("/api/v1/ping").statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());

        JsonNode changedAdmin = admin.post("/api/v1/auth/change-password", """
                {"currentPassword":"Initial-Admin-2026!","newPassword":"Changed-Admin-2026!"}
                """);
        assertThat(changedAdmin.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(admin.get("/api/v1/ping").statusCode()).isEqualTo(HttpStatus.OK.value());

        HttpServer fakeModel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeModel.createContext("/models", exchange -> { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().close(); });
        fakeModel.createContext("/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String content = request.contains("Elasticsearch 查询规划器")
                    ? (request.contains("上一次生成的查询计划未通过校验") ? "{\"filters\":[{\"field\":\"title\",\"operator\":\"match\",\"value\":\"测试\"}],\"size\":10}" : request.contains("截断测试") ? "{\"filters\":[" : "{\"filters\":[{\"field\":\"title\",\"operator\":\"match\",\"value\":\"测试\"}],\"size\":10}")
                    : "查询到 1 条符合条件的记录。";
            byte[] body = mapper.writeValueAsBytes(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("message", java.util.Map.of("content", content)))));
            exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.getResponseBody().close();
        });
        fakeModel.start();
        JsonNode model = admin.post("/api/v1/admin/models", mapper.writeValueAsString(java.util.Map.of(
                "displayName", "测试模型", "provider", "OPENAI", "endpoint", "http://127.0.0.1:" + fakeModel.getAddress().getPort(),
                "modelName", "test-chat", "apiKey", "secret-value", "timeoutMs", 5000)));
        admin.post("/api/v1/admin/models/" + model.get("id").asText() + "/tests", "{}");
        admin.post("/api/v1/admin/models/" + model.get("id").asText() + "/enable", "{}");
        assertThat(admin.get("/api/v1/admin/models").body()).doesNotContain("secret-value");
        byte[] ciphertext = jdbc.queryForObject("SELECT ciphertext FROM secret_version WHERE secret_kind='MODEL'", byte[].class);
        assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("secret-value");
        admin.put("/api/v1/admin/models/" + model.get("id").asText(), mapper.writeValueAsString(java.util.Map.of(
                "displayName", "测试模型已修改", "provider", "OPENAI", "endpoint", "http://127.0.0.1:" + fakeModel.getAddress().getPort(),
                "modelName", "test-chat-v2", "apiKey", "", "timeoutMs", 5000)));
        assertThat(admin.get("/api/v1/admin/models").body()).contains("测试模型已修改", "UNTESTED");
        admin.post("/api/v1/admin/models/" + model.get("id").asText() + "/tests", "{}");
        admin.post("/api/v1/admin/models/" + model.get("id").asText() + "/enable", "{}");
        JsonNode disposableModel = admin.post("/api/v1/admin/models", mapper.writeValueAsString(java.util.Map.of(
                "displayName", "待删除模型", "provider", "OPENAI", "endpoint", "http://127.0.0.1:" + fakeModel.getAddress().getPort(),
                "modelName", "delete-me", "apiKey", "", "timeoutMs", 5000)));
        admin.delete("/api/v1/admin/models/" + disposableModel.get("id").asText());
        assertThat(admin.get("/api/v1/admin/models").body()).doesNotContain("待删除模型");
        JsonNode embedding = admin.post("/api/v1/admin/embedding-models", mapper.writeValueAsString(java.util.Map.of(
                "displayName", "测试向量模型", "provider", "OPENAI", "endpoint", "http://127.0.0.1:" + fakeModel.getAddress().getPort(),
                "modelName", "test-embedding", "apiKey", "embedding-secret", "timeoutMs", 5000)));
        admin.post("/api/v1/admin/embedding-models/" + embedding.get("id").asText() + "/tests", "{}");
        admin.post("/api/v1/admin/embedding-models/" + embedding.get("id").asText() + "/enable", "{}");
        JsonNode knowledge = admin.post("/api/v1/admin/knowledge-bases", mapper.writeValueAsString(java.util.Map.of(
                "name", "测试知识库", "description", "验证目录与向量模型关联", "embeddingId", embedding.get("id").asText())));
        Map<String,Object> uploaded = documents.upload(UUID.fromString(knowledge.get("id").asText()), new MockMultipartFile("file","policy.txt","text/plain","销售额以支付时间统计。".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String jobId=String.valueOf(uploaded.get("jobId"));
        for(int i=0;i<30;i++){String status=jdbc.queryForObject("SELECT status FROM document_job WHERE id=?",String.class,jobId);if("COMPLETED".equals(status))break;Thread.sleep(100);}
        assertThat(jdbc.queryForObject("SELECT parse_status FROM document_version WHERE id=?",String.class,String.valueOf(uploaded.get("versionId")))).isEqualTo("PARSED");
        fakeModel.createContext("/test-index", exchange -> { byte[] body=("POST".equals(exchange.getRequestMethod())?"{\"took\":3,\"timed_out\":false,\"hits\":{\"total\":{\"value\":1},\"hits\":[{\"_id\":\"1\",\"_score\":1.0,\"_source\":{\"title\":\"测试记录\",\"published_at\":\"2026-09-24\"}}]}}":"{\"test-index\":{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"},\"published_at\":{\"type\":\"date\"}}}}}").getBytes();exchange.sendResponseHeaders(200, body.length);exchange.getResponseBody().write(body);exchange.getResponseBody().close(); });
        JsonNode source = admin.post("/api/v1/admin/data-sources", mapper.writeValueAsString(java.util.Map.of(
                "name", "测试 ES", "type", "ELASTICSEARCH", "endpoint", "http://127.0.0.1:" + fakeModel.getAddress().getPort(),
                "indexName", "test-index", "username", "elastic", "password", "source-secret", "timezone", "Asia/Shanghai", "purpose", "集成测试")));
        admin.post("/api/v1/admin/data-sources/" + source.get("id").asText() + "/tests", "{}");
        admin.post("/api/v1/admin/data-sources/" + source.get("id").asText() + "/schema-refresh", "{}");
        admin.put("/api/v1/admin/data-sources/" + source.get("id").asText() + "/semantics", "{\"allowedObjects\":{\"fields\":[\"title\",\"published_at\"],\"fieldSemantics\":{\"title\":{\"displayName\":\"新闻标题\",\"description\":\"新闻的标题\",\"synonyms\":[\"标题\"],\"searchField\":\"title\",\"fullText\":true}}},\"metrics\":[]}");
        admin.post("/api/v1/admin/data-sources/" + source.get("id").asText() + "/publish", "{}");
        assertThat(admin.get("/api/v1/admin/data-sources").body()).doesNotContain("source-secret");
        JsonNode conversation = admin.post("/api/v1/chat/conversations", mapper.writeValueAsString(java.util.Map.of("modelId", model.get("id").asText(), "sourceId", source.get("id").asText())));
        JsonNode task = admin.post("/api/v1/chat/conversations/" + conversation.get("id").asText() + "/messages", "{\"content\":\"查找测试记录\"}");
        String analysisStatus = "QUEUED";
        for(int i=0;i<60;i++){analysisStatus=jdbc.queryForObject("SELECT status FROM analysis_task WHERE id=?",String.class,task.get("taskId").asText());if(!java.util.List.of("QUEUED","RUNNING").contains(analysisStatus))break;Thread.sleep(100);}
        assertThat(analysisStatus).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT query_language FROM chat_message WHERE id=(SELECT assistant_message_id FROM analysis_task WHERE id=?)",String.class,task.get("taskId").asText())).isEqualTo("DSL");
        JsonNode truncatedTask = admin.post("/api/v1/chat/conversations/" + conversation.get("id").asText() + "/messages", "{\"content\":\"截断测试\"}");
        String truncatedStatus="QUEUED";
        for(int i=0;i<60;i++){truncatedStatus=jdbc.queryForObject("SELECT status FROM analysis_task WHERE id=?",String.class,truncatedTask.get("taskId").asText());if(!java.util.List.of("QUEUED","RUNNING").contains(truncatedStatus))break;Thread.sleep(100);}
        assertThat(truncatedStatus).isEqualTo("COMPLETED");
        fakeModel.stop(0);

        JsonNode created = admin.post("/api/v1/admin/users", """
                {"username":"analyst.one","displayName":"分析员一号","role":"USER"}
                """);
        String temporaryPassword = created.get("temporaryPassword").asText();
        assertThat(temporaryPassword).hasSize(18);

        Client user = new Client();
        assertThat(user.login("analyst.one", temporaryPassword).get("mustChangePassword").asBoolean()).isTrue();
        assertThat(user.get("/api/v1/admin/users").statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(user.get("/api/v1/ping").statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());

        JsonNode changedUser = user.post("/api/v1/auth/change-password", mapper.writeValueAsString(
                java.util.Map.of("currentPassword", temporaryPassword, "newPassword", "Analyst-New-2026!")));
        assertThat(changedUser.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(user.get("/api/v1/ping").statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(user.get("/api/v1/admin/users").statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(user.get("/api/v1/chat/options").body()).doesNotContain("测试模型已修改","测试 ES");

        String userId=created.get("user").get("id").asText();
        admin.put("/api/v1/admin/users/"+userId+"/grants",mapper.writeValueAsString(java.util.Map.of(
                "models",java.util.List.of(model.get("id").asText()),"dataSources",java.util.List.of(source.get("id").asText()),"knowledgeBases",java.util.List.of())));
        assertThat(user.get("/api/v1/chat/options").body()).contains("测试模型已修改","测试 ES");
        JsonNode userConversation=user.post("/api/v1/chat/conversations",mapper.writeValueAsString(java.util.Map.of("modelId",model.get("id").asText(),"sourceId",source.get("id").asText())));
        admin.put("/api/v1/admin/users/"+userId+"/grants","{\"models\":[],\"dataSources\":[],\"knowledgeBases\":[]}");
        assertThat(user.get("/api/v1/chat/conversations/"+userConversation.get("id").asText()+"/messages").statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());

        JsonNode reset = admin.post("/api/v1/admin/users/" + userId + "/reset-password", "{}");
        assertThat(user.get("/api/v1/ping").statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        Client relogin = new Client();
        assertThat(relogin.login("analyst.one", reset.get("temporaryPassword").asText()).get("mustChangePassword").asBoolean()).isTrue();
        for(int i=0;i<5;i++)assertThat(new Client().loginResponse("analyst.one","wrong-password").statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(new Client().loginResponse("analyst.one",reset.get("temporaryPassword").asText()).statusCode()).isEqualTo(423);
        admin.post("/api/v1/admin/users/"+userId+"/unlock","{}");
        assertThat(new Client().login("analyst.one",reset.get("temporaryPassword").asText()).get("username").asText()).isEqualTo("analyst.one");
    }

    private final class Client {
        private final HttpClient http;
        private String csrf;

        private Client() {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            http = HttpClient.newBuilder().cookieHandler(cookies).build();
        }

        JsonNode login(String username, String password) throws Exception {
            refreshCsrf();
            return post("/api/v1/auth/login", mapper.writeValueAsString(java.util.Map.of("username", username, "password", password)));
        }

        HttpResponse<String> loginResponse(String username,String password)throws Exception{refreshCsrf();HttpRequest request=HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(java.util.Map.of("username",username,"password",password)))).build();return http.send(request,HttpResponse.BodyHandlers.ofString());}

        void refreshCsrf() throws Exception {
            HttpResponse<String> response = get("/api/v1/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            csrf = mapper.readTree(response.body()).get("token").asText();
        }

        JsonNode post(String path, String body) throws Exception {
            return send(path, body, "POST");
        }

        JsonNode put(String path, String body) throws Exception {
            return send(path, body, "PUT");
        }

        JsonNode delete(String path) throws Exception {
            return send(path, "", "DELETE");
        }

        JsonNode send(String path, String body, String method) throws Exception {
            if (csrf == null) refreshCsrf();
            HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", csrf).method(method, HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
            refreshCsrf();
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    }
}
