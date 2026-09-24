package local.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Real AgentScope provider and ReAct code against local protocol fixtures, not real-model quality. */
class ProviderProtocolTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ExecutionConfig ONCE = ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(5)).build();

    public static class EvidenceTool {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(name = "lookup_total", description = "Read synthetic sales total", readOnly = true)
        public String lookup(@ToolParam(name = "region", description = "Region") String region) {
            calls.incrementAndGet();
            if (!"east".equals(region)) throw new IllegalArgumentException("Unexpected fixture region");
            return "{\"evidenceId\":\"fixture-e1\",\"total\":42}";
        }
    }

    static ReActAgent agent(Model model, EvidenceTool tool) {
        return agent(model, tool, ONCE);
    }

    static ReActAgent agent(Model model, EvidenceTool tool, ExecutionConfig modelExecution) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        return ReActAgent.builder().name("compatibility-probe").sysPrompt("Use lookup_total, then report its evidence.")
                .model(model).toolkit(toolkit).maxIters(3)
                .modelExecutionConfig(modelExecution).toolExecutionConfig(ONCE)
                .generateOptions(GenerateOptions.builder().parallelToolCalls(false).build()).build();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void openAiToolRoundTripAndEvents(boolean streaming) throws Exception {
        try (Fixture server = new Fixture("openai", streaming, false)) {
            EvidenceTool tool = new EvidenceTool();
            var model = OpenAIChatModel.builder().modelName("fixture-openai").apiKey("fixture-key")
                    .baseUrl(server.url() + "/v1").stream(streaming).build();
            try (var agent = agent(model, tool)) {
                List<AgentEvent> events = agent.streamEvents(new UserMessage("Read east total"))
                        .collectList().block(Duration.ofSeconds(12));
                assertRoundTrip(events, tool, server);
                assertThat(server.paths).allMatch(p -> p.equals("/v1/chat/completions"));
                assertThat(server.authorizations).containsOnly("Bearer fixture-key");
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ollamaToolRoundTripAndEvents(boolean streaming) throws Exception {
        try (Fixture server = new Fixture("ollama", streaming, false)) {
            EvidenceTool tool = new EvidenceTool();
            var model = OllamaChatModel.builder().modelName("fixture-ollama").baseUrl(server.url()).stream(streaming).build();
            try (var agent = agent(model, tool)) {
                List<AgentEvent> events = agent.streamEvents(new UserMessage("Read east total"))
                        .collectList().block(Duration.ofSeconds(12));
                assertRoundTrip(events, tool, server);
                assertThat(server.paths).containsOnly("/api/chat");
            }
        }
    }

    static void assertRoundTrip(List<AgentEvent> events, EvidenceTool tool, Fixture server) {
        assertThat(tool.calls.get()).isEqualTo(1);
        assertThat(server.requests.size()).isEqualTo(2);
        assertThat(server.requests.get(1).toString()).contains("fixture-e1");
        assertThat(events).anyMatch(e -> e instanceof ToolResultEndEvent t && t.getState() == ToolResultState.SUCCESS);
        String text = events.stream().filter(TextBlockDeltaEvent.class::isInstance).map(TextBlockDeltaEvent.class::cast)
                .map(TextBlockDeltaEvent::getDelta).reduce("", String::concat);
        assertThat(text).contains("42");
        assertThat(events.stream().map(e -> e.getType().name())).contains("AGENT_START", "AGENT_END");
    }

    @Test void explicitNoRetryDoesNotRepeatProviderFailure() throws Exception {
        try (Fixture server = new Fixture("openai", false, true)) {
            var model = OpenAIChatModel.builder().modelName("fixture").apiKey("fixture-key").baseUrl(server.url()+"/v1").stream(false).build();
            try (var agent = agent(model, new EvidenceTool())) {
                assertThatThrownBy(() -> agent.call(new UserMessage("fail")).block(Duration.ofSeconds(10)))
                        .isInstanceOf(RuntimeException.class);
                assertThat(server.requests).hasSize(1);
            }
        }
    }

    @Test void perVersionClientsDoNotMixEndpointModelOrCredential() throws Exception {
        try (Fixture first = new Fixture("openai", false, false); Fixture second = new Fixture("openai", false, false)) {
            try (var a = agent(OpenAIChatModel.builder().modelName("model-a").apiKey("key-a").baseUrl(first.url()+"/v1").stream(false).build(), new EvidenceTool());
                 var b = agent(OpenAIChatModel.builder().modelName("model-b").apiKey("key-b").baseUrl(second.url()+"/v1").stream(false).build(), new EvidenceTool())) {
                CompletableFuture.allOf(a.call(new UserMessage("east")).toFuture(), b.call(new UserMessage("east")).toFuture()).get(12, TimeUnit.SECONDS);
                assertThat(first.authorizations).containsOnly("Bearer key-a");
                assertThat(second.authorizations).containsOnly("Bearer key-b");
                assertThat(first.requests).allMatch(n -> n.path("model").asText().equals("model-a"));
                assertThat(second.requests).allMatch(n -> n.path("model").asText().equals("model-b"));
            }
        }
    }

    @Test void modelTimeoutUsesExplicitBudgetWithoutRetry() throws Exception {
        try(Fixture server=new Fixture("openai",false,false)) {
            server.hold=true;
            var budget=ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofMillis(250)).build();
            try(var agent=agent(OpenAIChatModel.builder().modelName("fixture").apiKey("fixture-key").baseUrl(server.url()+"/v1").stream(false).build(),new EvidenceTool(),budget)) {
                long started=System.nanoTime();
                assertThatThrownBy(()->agent.call(new UserMessage("wait")).block(Duration.ofSeconds(3)))
                        .isInstanceOf(RuntimeException.class);
                assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
                assertThat(server.requests).hasSize(1);
            }
        }
    }

    public record StructuredAnswer(int total, String evidenceId) {}

    @Test void structuredOutputToolProducesTypedResult() throws Exception {
        try(Fixture server=new Fixture("openai",false,false)) {
            server.structured=true;
            try(var agent=agent(OpenAIChatModel.builder().modelName("fixture").apiKey("fixture-key").baseUrl(server.url()+"/v1").stream(false)
                    .nativeStructuredOutput(false).nativeStructuredOutputWithTools(false).build(),new EvidenceTool())) {
                var answer=agent.call(List.of(new UserMessage("Return structured fixture data")),StructuredAnswer.class).block(Duration.ofSeconds(10));
                assertThat(answer.hasStructuredData()).isTrue();
                assertThat(answer.getStructuredData(StructuredAnswer.class)).isEqualTo(new StructuredAnswer(42,"fixture-e1"));
                assertThat(server.requests.getFirst().toString()).contains("generate_response");
            }
        }
    }

    @Test void applicationCancellationMustDisposeBlockedProviderSubscription() throws Exception {
        try (Fixture server = new Fixture("openai", false, false)) {
            server.hold = true;
            try (var agent = agent(OpenAIChatModel.builder().modelName("fixture").apiKey("fixture-key").baseUrl(server.url()+"/v1").stream(false).build(), new EvidenceTool(),
                    ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(15)).build())) {
                RuntimeContext first = RuntimeContext.builder().userId("user-a").sessionId("session-a").build();
                RuntimeContext second = RuntimeContext.builder().userId("user-b").sessionId("session-b").build();
                var pending = agent.call(List.of(new UserMessage("wait")), first).toFuture();
                assertThat(server.entered.await(4, TimeUnit.SECONDS)).isTrue();
                var other = agent.call(List.of(new UserMessage("second")), second).toFuture();
                assertThat(server.twoEntered.await(3, TimeUnit.SECONDS)).isTrue();
                agent.interrupt(first);
                // 2.0.1 interrupt marks the session, but does not itself unblock a pending HTTP response.
                assertThatThrownBy(() -> pending.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(pending.cancel(true)).isTrue(); // Reactor subscription disposal at the application boundary.
                assertThat(pending.isDone()).isTrue();
                assertThat(other.isDone()).as("Interrupt must not stop the other session").isFalse();
                agent.interrupt(second);
                assertThat(other.cancel(true)).isTrue();
                assertThat(server.requests).hasSize(2);
            } finally { server.release.countDown(); }
        }
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<String> authorizations = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch twoEntered = new CountDownLatch(2);
        final String provider; final boolean streaming, fail;
        volatile boolean hold, structured;
        Fixture(String provider, boolean streaming, boolean fail) throws IOException {
            this.provider=provider; this.streaming=streaming; this.fail=fail;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle); server.setExecutor(executor); server.start();
        }
        String url() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        void handle(HttpExchange exchange) throws IOException {
            try {
                JsonNode input = JSON.readTree(exchange.getRequestBody());
                requests.add(input); paths.add(exchange.getRequestURI().getPath());
                authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                entered.countDown();twoEntered.countDown();
                if (hold) { try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                if (fail) { send(exchange, 500, "application/json", "{\"error\":{\"message\":\"synthetic failure\",\"type\":\"server_error\"}}"); return; }
                if(structured) {
                    send(exchange,200,"application/json","{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"structured-1\",\"type\":\"function\",\"function\":{\"name\":\"generate_response\",\"arguments\":\"{\\\"response\\\":{\\\"total\\\":42,\\\"evidenceId\\\":\\\"fixture-e1\\\"}}\"}}]},\"finish_reason\":\"tool_calls\"}]}");return;
                }
                boolean hasResult = input.path("messages").toString().contains("fixture-e1");
                String body;
                if (provider.equals("openai")) {
                    String content = hasResult ? "\"content\":\"Total is 42; source fixture-e1\"" :
                            "\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup_total\",\"arguments\":\"{\\\"region\\\":\\\"east\\\"}\"}}]";
                    if (streaming) {
                        content = content.replace("{\"id\":\"call-1\"", "{\"index\":0,\"id\":\"call-1\"");
                        body = "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","+content+"},\"finish_reason\":null}]}\n\n"+
                                "data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""+(hasResult?"stop":"tool_calls")+"\"}]}\n\ndata: [DONE]\n\n";
                    } else body = "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","+content+"},\"finish_reason\":\""+(hasResult?"stop":"tool_calls")+"\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5,\"total_tokens\":10}}";
                } else {
                    String content = hasResult ? "\"content\":\"Total is 42; source fixture-e1\"" :
                            "\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"lookup_total\",\"arguments\":{\"region\":\"east\"}}}]";
                    body = "{\"model\":\"fixture\",\"created_at\":\"2026-09-23T00:00:00Z\",\"message\":{\"role\":\"assistant\","+content+"},\"done\":"+(!streaming)+",\"done_reason\":\"stop\"}\n";
                    if (streaming) body += "{\"model\":\"fixture\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"eval_count\":5,\"prompt_eval_count\":5}\n";
                }
                send(exchange, 200, streaming ? (provider.equals("openai")?"text/event-stream":"application/x-ndjson") : "application/json", body);
            } finally { exchange.close(); }
        }
        static void send(HttpExchange exchange,int code,String type,String body) throws IOException {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type",type);
            exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
        }
        public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
