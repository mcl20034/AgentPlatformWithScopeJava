package com.agentplatform.common;

import java.sql.Timestamp;
import java.time.Instant;

/** Values suitable for binding to SQL TIMESTAMP/TIMESTAMP WITH TIME ZONE columns. */
public final class DatabaseTime {
    private DatabaseTime() {
    }

    public static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
