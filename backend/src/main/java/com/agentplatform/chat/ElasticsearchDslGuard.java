package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

final class ElasticsearchDslGuard {
    private static final Set<String> ROOT = Set.of("query", "aggs", "aggregations", "size", "sort", "_source", "track_total_hits");
    private static final Set<String> BLOCKED = Set.of("script", "script_fields", "runtime_mappings", "rescore", "pit", "search_after", "stored_fields", "docvalue_fields");
    private static final Set<String> FIELD_CLAUSES = Set.of("term", "terms", "range", "match", "match_phrase", "prefix", "wildcard", "regexp");
    private ElasticsearchDslGuard() {}

    static String validate(String raw, Set<String> allowedFields, ObjectMapper json) {
        try {
            String value = raw == null ? "" : raw.trim().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("(?s)\\s*```$", "");
            int first = value.indexOf('{'), last = value.lastIndexOf('}');
            if (first < 0 || last < first) throw rejected("模型未返回 JSON DSL");
            JsonNode root = json.readTree(value.substring(first, last + 1));
            if (!root.isObject()) throw rejected("Elasticsearch DSL 必须是 JSON 对象");
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) if (!ROOT.contains(names.next())) throw rejected("DSL 包含不允许的顶层参数");
            inspect(root, allowedFields);
            ObjectNode safe = (ObjectNode) root;
            safe.put("size", Math.min(Math.max(safe.path("size").asInt(50), 0), 200));
            return json.writeValueAsString(safe);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw rejected("Elasticsearch DSL 格式不正确"); }
    }

    private static void inspect(JsonNode node, Set<String> allowedFields) {
        if (node.isArray()) { node.forEach(child -> inspect(child, allowedFields)); return; }
        if (!node.isObject()) return;
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (BLOCKED.contains(key.toLowerCase(Locale.ROOT))) throw rejected("DSL 包含禁止的能力：" + key);
            // Query clauses use the field name as the object key, while aggregation
            // clauses such as {"terms":{"field":"site","order":...,"size":...}}
            // carry it in the dedicated "field" property. Do not treat aggregation
            // options as business fields.
            if (FIELD_CLAUSES.contains(key) && entry.getValue().isObject() && !entry.getValue().has("field")) entry.getValue().fieldNames().forEachRemaining(field -> requireField(field, allowedFields));
            if ("exists".equals(key) && entry.getValue().has("field")) requireField(entry.getValue().path("field").asText(), allowedFields);
            if ("field".equals(key) && entry.getValue().isTextual()) requireField(entry.getValue().asText(), allowedFields);
            if ("_source".equals(key) || "fields".equals(key)) requireFieldValues(entry.getValue(), allowedFields);
            if ("sort".equals(key)) requireSortFields(entry.getValue(), allowedFields);
            if ("path".equals(key) && entry.getValue().isTextual()) requireField(entry.getValue().asText(), allowedFields);
            inspect(entry.getValue(), allowedFields);
        });
    }
    private static void requireFieldValues(JsonNode value, Set<String> allowedFields) {
        if (value.isTextual()) requireField(value.asText(), allowedFields);
        else if (value.isArray()) value.forEach(field -> {
            if (!field.isTextual()) throw rejected("字段列表只允许字符串");
            requireField(field.asText(), allowedFields);
        });
        else if (!value.isBoolean()) throw rejected("字段列表格式不正确");
    }
    private static void requireSortFields(JsonNode value, Set<String> allowedFields) {
        if (!value.isArray()) throw rejected("sort 必须是数组");
        value.forEach(sort -> {
            if (sort.isTextual()) requireField(sort.asText(), allowedFields);
            else if (sort.isObject()) sort.fieldNames().forEachRemaining(field -> requireField(field, allowedFields));
            else throw rejected("sort 格式不正确");
        });
    }
    private static void requireField(String field, Set<String> allowedFields) {
        String normalized = field.endsWith(".keyword") ? field.substring(0, field.length() - 8) : field;
        if (!allowedFields.contains(field) && !allowedFields.contains(normalized)) throw rejected("DSL 访问了授权范围之外的字段：" + field);
    }
    private static BusinessException rejected(String message) { return new BusinessException(422, "DSL_REJECTED", message); }
}
