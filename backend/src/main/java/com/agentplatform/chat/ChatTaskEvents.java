package com.agentplatform.chat;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
class ChatTaskEvents {
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    SseEmitter subscribe(UUID taskId, Map<String, Object> current) {
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        send(emitter, current);
        if (finished(current)) emitter.complete();
        return emitter;
    }

    void publish(UUID taskId, Map<String, Object> event) {
        List<SseEmitter> list = emitters.getOrDefault(taskId, List.of());
        for (SseEmitter emitter : list) {
            if (!send(emitter, event)) remove(taskId, emitter);
            else if (finished(event)) emitter.complete();
        }
    }

    private boolean send(SseEmitter emitter, Map<String, Object> event) {
        try { emitter.send(SseEmitter.event().name("progress").data(event)); return true; }
        catch (IOException | IllegalStateException ignored) { return false; }
    }
    private void remove(UUID id, SseEmitter emitter) { emitters.computeIfPresent(id, (key, list) -> { list.remove(emitter); return list.isEmpty() ? null : list; }); }
    private static boolean finished(Map<String, Object> event) { return List.of("COMPLETED", "FAILED", "CANCELLED").contains(String.valueOf(event.get("status"))); }
}
