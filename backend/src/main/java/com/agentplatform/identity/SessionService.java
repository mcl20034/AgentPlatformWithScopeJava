package com.agentplatform.identity;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    public void invalidateUser(String username, String exceptSessionId) {
        sessions.findByPrincipalName(username).forEach((id, session) -> {
            if (exceptSessionId == null || !exceptSessionId.equals(id)) sessions.deleteById(id);
        });
    }
}
