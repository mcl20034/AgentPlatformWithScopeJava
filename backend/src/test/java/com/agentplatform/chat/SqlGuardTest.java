package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {
    @Test
    void permitsOneSelectWithinScopeAndAppliesHardLimit() {
        String sql = SqlGuard.validate("SELECT o.id, c.name FROM orders o JOIN customers c ON c.id=o.customer_id",
                Set.of("orders", "customers"));
        assertThat(sql).startsWith("SELECT * FROM (").endsWith("platform_query LIMIT 200");
        String cte = SqlGuard.validate("WITH recent AS (SELECT id FROM orders) SELECT * FROM recent", Set.of("orders"));
        assertThat(cte).contains("WITH recent AS");
    }

    @Test
    void rejectsWritesMultipleStatementsAndTablesOutsideScope() {
        assertThatThrownBy(() -> SqlGuard.validate("DELETE FROM orders", Set.of("orders"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SqlGuard.validate("SELECT * FROM orders; DROP TABLE orders", Set.of("orders"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SqlGuard.validate("SELECT * FROM secrets", Set.of("orders"))).isInstanceOf(BusinessException.class);
    }
}
