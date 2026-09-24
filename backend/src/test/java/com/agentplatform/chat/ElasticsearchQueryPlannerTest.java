package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchQueryPlannerTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void mapsBusinessFieldToPurposeSpecificFields()throws Exception{
        JsonNode semantics=json.readTree("""
          {"site":{"displayName":"发布站点","synonyms":["媒体","网站"],"filterField":"site.keyword","aggregationField":"site.keyword","aggregatable":true,"filterable":true},
           "published_at":{"displayName":"发布时间","filterField":"published_at","aggregationField":"published_at","filterable":true,"aggregatable":true}}
          """);
        String dsl=ElasticsearchQueryPlanner.build("""
          {"timeRange":{"field":"published_at","gte":"now-7d/d","lte":"now","timeZone":"Asia/Shanghai"},
           "groupBy":[{"field":"site","size":20}],"dateHistogram":{"field":"published_at","calendarInterval":"day"}}
          """,Set.of("site","published_at"),semantics,json);
        assertThat(dsl).contains("site.keyword","now-7d/d","date_histogram","calendar_interval");
    }

    @Test void rejectsUnauthorizedOrUnsupportedUse()throws Exception{
        JsonNode semantics=json.readTree("{\"title\":{\"aggregationField\":\"title\",\"aggregatable\":false}}");
        assertThatThrownBy(()->ElasticsearchQueryPlanner.build("{\"groupBy\":[{\"field\":\"title\"}]}",Set.of("title"),semantics,json)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ElasticsearchQueryPlanner.build("{\"filters\":[{\"field\":\"secret\",\"operator\":\"term\",\"value\":\"x\"}]}",Set.of("title"),semantics,json)).isInstanceOf(BusinessException.class);
    }
}
