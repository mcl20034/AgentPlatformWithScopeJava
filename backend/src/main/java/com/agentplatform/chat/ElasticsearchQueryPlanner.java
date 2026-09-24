package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

final class ElasticsearchQueryPlanner {
    private ElasticsearchQueryPlanner() {}

    static String build(String raw, Set<String> allowedFields, JsonNode semantics, ObjectMapper json) {
        try {
            String value=raw==null?"":raw.trim().replaceFirst("(?s)^```(?:json)?\\s*","").replaceFirst("(?s)\\s*```$","");
            int first=value.indexOf('{'),last=value.lastIndexOf('}');
            if(first<0||last<first)throw rejected("模型未返回完整的查询计划 JSON");
            JsonNode plan=json.readTree(value.substring(first,last+1));
            if(!plan.isObject())throw rejected("查询计划必须是 JSON 对象");
            ObjectNode root=json.createObjectNode(),bool=json.createObjectNode();
            ArrayNode filter=json.createArrayNode(),must=json.createArrayNode();
            for(JsonNode item:plan.path("filters")){
                String field=item.path("field").asText(),operator=item.path("operator").asText("term");
                if(Set.of("match","match_phrase").contains(operator)){
                    String actual=useField(field,"searchField","fullText",allowedFields,semantics);
                    must.add(json.createObjectNode().set(operator,json.createObjectNode().set(actual,item.get("value"))));
                }else if(Set.of("term","terms").contains(operator)){
                    String actual=useField(field,"filterField","filterable",allowedFields,semantics);
                    filter.add(json.createObjectNode().set(operator,json.createObjectNode().set(actual,item.get("value"))));
                }else if("range".equals(operator)){
                    String actual=useField(field,"filterField","filterable",allowedFields,semantics);
                    ObjectNode range=json.createObjectNode();copy(item,range,"gte");copy(item,range,"gt");copy(item,range,"lte");copy(item,range,"lt");
                    if(range.isEmpty())throw rejected("范围条件缺少边界");
                    filter.add(json.createObjectNode().set("range",json.createObjectNode().set(actual,range)));
                }else throw rejected("不支持的过滤操作："+operator);
            }
            JsonNode time=plan.path("timeRange");
            if(time.isObject()&&!time.path("field").asText().isBlank()){
                String actual=useField(time.path("field").asText(),"filterField","filterable",allowedFields,semantics);
                ObjectNode range=json.createObjectNode();copy(time,range,"gte");copy(time,range,"lte");
                if(!time.path("timeZone").asText().isBlank())range.put("time_zone",time.path("timeZone").asText());
                filter.add(json.createObjectNode().set("range",json.createObjectNode().set(actual,range)));
            }
            if(!filter.isEmpty())bool.set("filter",filter);if(!must.isEmpty())bool.set("must",must);
            ObjectNode query=json.createObjectNode();if(bool.isEmpty())query.putObject("match_all");else query.set("bool",bool);root.set("query",query);
            int size=Math.min(Math.max(plan.path("size").asInt(20),0),200);root.put("size",size);
            if(plan.path("sourceFields").isArray()){
                ArrayNode source=json.createArrayNode();for(JsonNode f:plan.path("sourceFields"))source.add(useField(f.asText(),"sourceField",null,allowedFields,semantics));root.set("_source",source);
            }
            if(plan.path("sort").isArray()){
                ArrayNode sorts=json.createArrayNode();for(JsonNode s:plan.path("sort")){String actual=useField(s.path("field").asText(),"sortField","sortable",allowedFields,semantics);sorts.add(json.createObjectNode().set(actual,json.createObjectNode().put("order","asc".equalsIgnoreCase(s.path("order").asText())?"asc":"desc")));}root.set("sort",sorts);
            }
            ObjectNode aggs=json.createObjectNode(),cursor=aggs;int index=0;
            for(JsonNode group:plan.path("groupBy")){
                String actual=useField(group.path("field").asText(),"aggregationField","aggregatable",allowedFields,semantics);
                ObjectNode definition=json.createObjectNode();definition.set("terms",json.createObjectNode().put("field",actual).put("size",Math.min(Math.max(group.path("size").asInt(20),1),100)));
                cursor.set("group_"+(++index),definition);cursor=definition.putObject("aggs");
            }
            JsonNode histogram=plan.path("dateHistogram");
            if(histogram.isObject()&&!histogram.path("field").asText().isBlank()){
                String actual=useField(histogram.path("field").asText(),"aggregationField","aggregatable",allowedFields,semantics);
                String interval=histogram.path("calendarInterval").asText("day");if(!Set.of("hour","day","week","month","quarter","year").contains(interval))throw rejected("不支持的时间聚合粒度");
                cursor.set("timeline",json.createObjectNode().set("date_histogram",json.createObjectNode().put("field",actual).put("calendar_interval",interval)));
            }
            if(!aggs.isEmpty()){root.set("aggs",aggs);if(!plan.has("size"))root.put("size",0);}
            return json.writeValueAsString(root);
        }catch(BusinessException e){throw e;}catch(Exception e){throw rejected("查询计划 JSON 不完整或格式错误");}
    }
    private static String useField(String requested,String preferred,String capability,Set<String> allowed,JsonNode semantics){
        if(requested==null||requested.isBlank())throw rejected("查询计划缺少字段");
        JsonNode definition=semantics.path(requested);String actual=definition.path(preferred).asText(requested);
        String base=actual.endsWith(".keyword")?actual.substring(0,actual.length()-8):actual;
        if(!allowed.contains(actual)&&!allowed.contains(base))throw rejected("查询计划使用了未授权字段："+actual);
        if(capability!=null&&definition.has(capability)&&!definition.path(capability).asBoolean())throw rejected("字段不支持当前用途："+requested);
        return actual;
    }
    private static void copy(JsonNode from,ObjectNode to,String name){if(from.has(name)&&!from.get(name).isNull())to.set(name,from.get(name));}
    private static BusinessException rejected(String message){return new BusinessException(422,"ES_PLAN_REJECTED",message);}
}
