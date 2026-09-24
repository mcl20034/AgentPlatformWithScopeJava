package local.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes=BootCompatibilityTest.App.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.main.banner-mode=off","logging.level.root=WARN","server.address=127.0.0.1"})
class BootCompatibilityTest {
    @LocalServerPort int port;
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class})
    @Import(ProbeController.class)
    static class App {
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            // This test exposes only synthetic GET endpoints; production authentication is not implemented here.
            return http.authorizeHttpRequests(auth->auth.anyRequest().permitAll()).build();
        }
    }
    @RestController static class ProbeController {
        @GetMapping("/probe/health") Map<String,String> health(){return Map.of("status","ok");}
        @GetMapping(value="/probe/events",produces="text/event-stream") SseEmitter events() throws Exception {
            SseEmitter emitter=new SseEmitter(3000L);
            emitter.send(SseEmitter.event().id("1").name("run.started").data(Map.of("synthetic",true)));
            emitter.send(SseEmitter.event().id("2").name("run.finished").data(Map.of("state","COMPLETED")));
            emitter.complete();return emitter;
        }
    }
    @Test void bootServletSecurityJacksonAndSseStartTogether() throws Exception {
        try(HttpClient client=HttpClient.newHttpClient()) {
            var health=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/probe/health")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);assertThat(health.body()).contains("\"status\":\"ok\"");
            var events=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/probe/events")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(events.statusCode()).isEqualTo(200);
            assertThat(events.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
            assertThat(events.body()).contains("id:1","event:run.started","id:2","event:run.finished");
        }
    }
}
