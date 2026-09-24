package com.agentplatform.chat;

import com.agentplatform.common.BusinessException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class SqlGuard {
    private static final Pattern DANGEROUS_FUNCTION = Pattern.compile("(?i)\\b(sleep|benchmark|load_file|get_lock|release_lock|is_free_lock|is_used_lock)\\s*\\(");

    private SqlGuard() {}

    static String validate(String raw, Set<String> allowedTables) {
        String sql = raw == null ? "" : raw.trim();
        if (sql.startsWith("```")) sql = sql.replaceFirst("(?s)^```(?:sql)?\\s*", "").replaceFirst("(?s)\\s*```$", "").trim();
        String lower = sql.toLowerCase(Locale.ROOT);
        int selectIndex = lower.indexOf("select");
        if (!lower.startsWith("select") && !lower.startsWith("with") && selectIndex > 0) sql = sql.substring(selectIndex);
        while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.contains("--") || sql.contains("/*") || sql.contains("#") || DANGEROUS_FUNCTION.matcher(sql).find()) throw rejected("SQL 包含禁止的注释或函数");
        Set<String> referenced = new LinkedHashSet<>();
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withAllowComplexParsing(false).withTimeOut(3000));
            if (statements.size() != 1 || !(statements.getFirst() instanceof Select parsedSelect)) throw rejected("仅允许单条 SELECT 查询");
            if (CCJSqlParserUtil.getNestingDepth(sql) > 12) throw rejected("SQL 嵌套层级超过限制");
            for (String table : new TablesNamesFinder<Void>().getTables((net.sf.jsqlparser.statement.Statement) parsedSelect)) {
                String normalized = table.replace("`", "");
                int dot = normalized.lastIndexOf('.');
                referenced.add((dot >= 0 ? normalized.substring(dot + 1) : normalized).toLowerCase(Locale.ROOT));
            }
            sql = parsedSelect.toString();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw rejected("SQL 无法通过 AST 解析"); }
        if (referenced.isEmpty()) throw rejected("SQL 未引用任何已授权表");
        Set<String> allowed = new LinkedHashSet<>();
        allowedTables.forEach(value -> allowed.add(value.toLowerCase(Locale.ROOT)));
        if (!allowed.containsAll(referenced)) throw rejected("SQL 访问了授权范围之外的表：" + referenced.stream().filter(t -> !allowed.contains(t)).toList());
        return "SELECT * FROM (" + sql + ") platform_query LIMIT 200";
    }

    private static BusinessException rejected(String message) {
        return new BusinessException(422, "SQL_REJECTED", message);
    }
}
