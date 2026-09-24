package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchDslGuardTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void acceptsAuthorizedQueryAndEnforcesSizeLimit(){
        String value=ElasticsearchDslGuard.validate("{\"query\":{\"range\":{\"published_at\":{\"gte\":\"now-1d\"}}},\"size\":1000}",Set.of("published_at"),json);
        assertThat(value).contains("\"size\":200");
        String aggregation=ElasticsearchDslGuard.validate("{\"size\":0,\"aggs\":{\"by_site\":{\"terms\":{\"field\":\"site\",\"order\":{\"_count\":\"desc\"},\"size\":20}}}}",Set.of("site"),json);
        assertThat(aggregation).contains("\"field\":\"site\"", "\"order\"");
    }
    @Test void rejectsScriptsAndUnauthorizedFields(){
        assertThatThrownBy(()->ElasticsearchDslGuard.validate("{\"query\":{\"script\":{\"script\":\"x\"}}}",Set.of("title"),json)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ElasticsearchDslGuard.validate("{\"query\":{\"term\":{\"secret\":\"x\"}}}",Set.of("title"),json)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ElasticsearchDslGuard.validate("{\"_source\":[\"title\",\"secret\"]}",Set.of("title"),json)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ElasticsearchDslGuard.validate("{\"sort\":[{\"secret\":\"desc\"}]}",Set.of("title"),json)).isInstanceOf(BusinessException.class);
    }
}
