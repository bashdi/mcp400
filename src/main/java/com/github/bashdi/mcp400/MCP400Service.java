package com.github.bashdi.mcp400;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Catalog-only access to Db2 for i. Nothing in here reads table data; every method returns a compact,
 * human/LLM readable text instead of JSON to save context.
 */
public class MCP400Service implements Reconnectable {

    private static final int MAX_ROWS = 300;
    private static final int MAX_SEARCH_TABLES = 25;
    private static final int MAX_COLUMNS_PER_SEARCH_HIT = 6;
    private static final String SYSTEM_SCHEMAS = "'QSYS','QSYS2','SYSIBM','SYSIBMADM','SYSPROC','SYSTOOLS','SYSCAT','SYSSTAT','QSYSINC','QHLPSYS','QTEMP','QMGTC','QMGTC2'";
    private static final Pattern SQL_COMMENTS = Pattern.compile("--[^\\r\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private volatile Connection connection;
    private KnowledgeService knowledge;

    public MCP400Service() throws SQLException {
        this.connection = openReadOnlyConnection();
    }

    /** Enables the "knowledge entries exist" hint in getInformationAboutTable. */
    void setKnowledge(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    /** Replaces a dead connection by a new one. */
    @Override
    public synchronized void reconnect() throws SQLException {
        try {
            connection.close();
        } catch (Exception ignored) {
            // already dead
        }
        connection = openReadOnlyConnection();
    }

    private static Connection openReadOnlyConnection() throws SQLException {
        Connection c = openConnection();
        c.setReadOnly(true);
        return c;
    }

    /** True if the exception (or a cause) says that the connection to the IBM i is gone. */
    static boolean isConnectionLost(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase();
                if ((state != null && state.startsWith("08")) || message.contains("communication link failure")
                        || message.contains("connection does not exist") || message.contains("connection is closed")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Opens a connection from the AS400_ADDRESS, AS400_USER and AS400_PASSWORD environment variables. */
    static Connection openConnection() throws SQLException {
        String address = System.getenv("AS400_ADDRESS");
        String username = System.getenv("AS400_USER");
        String password = System.getenv("AS400_PASSWORD");

        if (address == null || username == null || password == null) {
            throw new IllegalStateException("AS400_ADDRESS, AS400_USER or AS400_PASSWORD missing!");
        }

        String connectionString = "jdbc:as400:" + address + ";prompt=false;translate binary=true;naming=system";
        return DriverManager.getConnection(connectionString, username, password);
    }

    // ------------------------------------------------------------------ tables

    public String getAllTablesForSchema(String schema, String filter, boolean includeLogicals) throws SQLException {
        schema = up(schema);
        StringBuilder sql = new StringBuilder("SELECT TABLE_NAME, SYSTEM_TABLE_NAME, TABLE_TYPE, TABLE_TEXT FROM QSYS2.SYSTABLES " +
                "WHERE TABLE_SCHEMA = ? AND COALESCE(FILE_TYPE, 'D') <> 'S'");
        List<String> params = new ArrayList<>(List.of(schema));
        if (!includeLogicals) {
            sql.append(" AND TABLE_TYPE <> 'L'");
        }
        if (filter != null && !filter.isBlank()) {
            sql.append(" AND (UPPER(TABLE_NAME) LIKE ? OR UPPER(SYSTEM_TABLE_NAME) LIKE ? OR UPPER(COALESCE(TABLE_TEXT, '')) LIKE ?)");
            String like = like(filter);
            params.addAll(List.of(like, like, like));
        }
        sql.append(" ORDER BY TABLE_NAME");

        List<Map<String, String>> rows = query(sql.toString(), MAX_ROWS + 1, params.toArray(new String[0]));
        if (rows.isEmpty()) {
            return "No objects found in schema " + schema + (filter != null && !filter.isBlank() ? " matching '" + filter + "'" : "")
                    + ". Check the schema (library) name or use searchTables / getSchemaForTable to locate the object.";
        }
        StringBuilder out = new StringBuilder();
        boolean truncated = rows.size() > MAX_ROWS;
        out.append(schema).append(": ").append(Math.min(rows.size(), MAX_ROWS)).append(truncated ? "+" : "").append(" objects");

        if (!includeLogicals) {
            out.append(" (logical files hidden)");
        }
        out.append('\n');
        for (int i = 0; i < Math.min(rows.size(), MAX_ROWS); i++) {
            out.append(tableLine(rows.get(i))).append('\n');
        }
        if (truncated) {
            out.append("... truncated, use filter.\n");
        }
        return out.toString();
    }

    public String searchTables(String pattern, String schema) throws SQLException {
        List<String> terms = terms(pattern);
        StringBuilder sql = new StringBuilder("SELECT TABLE_SCHEMA, TABLE_NAME, SYSTEM_TABLE_NAME, TABLE_TYPE, TABLE_TEXT FROM QSYS2.SYSTABLES WHERE (");
        List<String> params = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("UPPER(TABLE_NAME) LIKE ? OR UPPER(SYSTEM_TABLE_NAME) LIKE ? OR UPPER(COALESCE(TABLE_TEXT, '')) LIKE ?");
            String like = like(terms.get(i));
            params.addAll(List.of(like, like, like));
        }
        sql.append(") AND TABLE_TYPE IN ('P','T','V','M') AND COALESCE(FILE_TYPE, 'D') <> 'S'");
        appendSchemaFilter(sql, params, schema, "TABLE_SCHEMA");
        sql.append(" ORDER BY TABLE_SCHEMA, TABLE_NAME");

        List<Map<String, String>> rows = query(sql.toString(), 51, params.toArray(new String[0]));
        if (rows.isEmpty()) {
            return "No tables or views found for '" + pattern + "'. Try other terms (also in German/English/abbreviations) or use searchColumns.";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(rows.size(), 50); i++) {
            Map<String, String> r = rows.get(i);
            out.append(g(r, "TABLE_SCHEMA")).append('.').append(tableLine(r)).append('\n');
        }
        if (rows.size() > 50) {
            out.append("... more, refine pattern or pass schema.\n");
        }
        return out.toString();
    }

    public String getSchemaForTable(String tableName) throws SQLException {
        tableName = up(tableName);
        List<Map<String, String>> rows = query("SELECT TABLE_SCHEMA, TABLE_NAME, SYSTEM_TABLE_NAME, TABLE_TYPE, TABLE_TEXT, LAST_ALTERED_TIMESTAMP FROM QSYS2.SYSTABLES " +
                        "WHERE (TABLE_NAME = ? OR SYSTEM_TABLE_NAME = ?) AND COALESCE(FILE_TYPE, 'D') <> 'S' AND TABLE_SCHEMA NOT IN (" + SYSTEM_SCHEMAS + ") " +
                        "ORDER BY LAST_ALTERED_TIMESTAMP DESC",
                51, tableName, tableName);
        if (rows.isEmpty()) {
            return "No object named " + tableName + " found in any user schema. Use searchTables to search by partial name or description.";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(rows.size(), 50); i++) {
            Map<String, String> r = rows.get(i);
            out.append(g(r, "TABLE_SCHEMA")).append('.').append(tableLine(r)).append(" [").append(date(g(r, "LAST_ALTERED_TIMESTAMP"))).append("]\n");
        }
        return out.toString();
    }

    public String getAllColumnsForTable(String schema, String tableName) throws SQLException {
        Resolved t = resolve(schema, tableName);
        List<Map<String, String>> rows = query("SELECT COLUMN_NAME, SYSTEM_COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, " +
                        "IS_NULLABLE, COLUMN_DEFAULT, COLUMN_TEXT, COLUMN_HEADING, CCSID, IS_IDENTITY " +
                        "FROM QSYS2.SYSCOLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                0, t.schema, t.name);

        StringBuilder out = new StringBuilder();
        out.append(t.header()).append(", ").append(rows.size()).append(" columns\n");
        for (Map<String, String> r : rows) {
            out.append(g(r, "COLUMN_NAME"));
            String sys = g(r, "SYSTEM_COLUMN_NAME");
            if (!sys.isEmpty() && !sys.equals(g(r, "COLUMN_NAME"))) {
                out.append(" (").append(sys).append(')');
            }
            out.append(' ').append(dataType(r));
            if ("Y".equals(g(r, "IS_NULLABLE"))) out.append('?');
            String def = g(r, "COLUMN_DEFAULT");
            if (!def.isEmpty()) out.append(" default=").append(cut(def, 40));
            if ("YES".equals(g(r, "IS_IDENTITY")) || "Y".equals(g(r, "IS_IDENTITY"))) out.append(" identity");
            if ("65535".equals(g(r, "CCSID"))) out.append(" ccsid=65535");
            String text = g(r, "COLUMN_TEXT");
            if (text.isEmpty()) text = clean(g(r, "COLUMN_HEADING"));
            if (!text.isEmpty()) out.append(" - ").append(text);
            out.append('\n');
        }
        return out.toString();
    }

    public String getInformationAboutTable(String schema, String tableName) throws SQLException {
        Resolved t = resolve(schema, tableName);
        StringBuilder out = new StringBuilder(t.header()).append('\n');

        switch (t.type) {
            case "L" -> out.append("Logical file: query the physical file instead unless its select/omit matters (getDependentObjects).\n");
            case "V" -> out.append("See getViewDefinition.\n");
            case "A" -> out.append("Alias, see getDependentObjects.\n");
            default -> { }
        }

        // size / usage
        try {
            List<Map<String, String>> stat = firstWorking(1, new String[]{
                    "SELECT * FROM QSYS2.SYSTABLESTAT WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                    "SELECT * FROM QSYS2.SYSPARTITIONSTAT WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"}, t.schema, t.name);
            if (!stat.isEmpty()) {
                Map<String, String> s = stat.get(0);
                out.append("Rows (approx.): ").append(g(s, "NUMBER_ROWS"));
                if (!g(s, "NUMBER_DELETED_ROWS").isEmpty()) out.append(", deleted rows: ").append(g(s, "NUMBER_DELETED_ROWS"));
                out.append(", last changed: ").append(date(g(s, "LAST_CHANGE_TIMESTAMP")));
                out.append(", last used: ").append(date(g(s, "LAST_USED_TIMESTAMP"))).append('\n');
            }
        } catch (SQLException ignored) {
            // statistics are optional
        }

        DatabaseMetaData meta = connection.getMetaData();

        // primary key
        List<String> pk = new ArrayList<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, t.schema, t.name)) {
            while (rs.next()) pk.add(rs.getString("COLUMN_NAME"));
        }
        out.append("Primary key: ").append(pk.isEmpty() ? "none" : String.join(", ", pk)).append('\n');

        // foreign keys (this table -> others)
        Map<String, List<String[]>> fks = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(null, t.schema, t.name)) {
            while (rs.next()) {
                fks.computeIfAbsent(rs.getString("FK_NAME"), k -> new ArrayList<>()).add(new String[]{
                        rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")});
            }
        }
        if (fks.isEmpty()) {
            out.append("Foreign keys: none (legacy relations are often implied by identical column names, see searchColumns)\n");
        } else {
            out.append("Foreign keys:\n");
            for (List<String[]> fk : fks.values()) {
                StringBuilder cols = new StringBuilder();
                StringBuilder target = new StringBuilder();
                for (String[] p : fk) {
                    if (cols.length() > 0) { cols.append(", "); target.append(", "); }
                    cols.append(p[0]);
                    target.append(p[3]);
                }
                out.append("  ").append(cols).append(" -> ").append(fk.get(0)[1]).append('.').append(fk.get(0)[2]).append('(').append(target).append(")\n");
            }
        }

        // constraints
        try {
            List<Map<String, String>> cst = query("SELECT c.CONSTRAINT_NAME, c.CONSTRAINT_TYPE, k.CHECK_CLAUSE FROM QSYS2.SYSCST c " +
                            "LEFT JOIN QSYS2.SYSCHKCST k ON k.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = c.CONSTRAINT_NAME " +
                            "WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE IN ('CHECK', 'UNIQUE')",
                    20, t.schema, t.name);
            if (!cst.isEmpty()) {
                out.append("Constraints:\n");
                for (Map<String, String> c : cst) {
                    out.append("  ").append(g(c, "CONSTRAINT_TYPE")).append(' ').append(g(c, "CONSTRAINT_NAME"));
                    if (!g(c, "CHECK_CLAUSE").isEmpty()) out.append(": ").append(cut(g(c, "CHECK_CLAUSE"), 200));
                    out.append('\n');
                }
            }
        } catch (SQLException ignored) {
            // optional
        }

        // indexes and keyed logical files
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        Set<String> unique = new HashSet<>();
        try (ResultSet rs = meta.getIndexInfo(null, t.schema, t.name, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (indexName == null || column == null) continue;
                if (!rs.getBoolean("NON_UNIQUE")) unique.add(indexName);
                String order = rs.getString("ASC_OR_DESC");
                indexes.computeIfAbsent(indexName, k -> new ArrayList<>()).add(column + ("D".equals(order) ? " DESC" : ""));
            }
        }
        if (indexes.isEmpty()) {
            out.append("Indexes: none found\n");
        } else {
            out.append("Indexes (key order):\n");
            int n = 0;
            for (Map.Entry<String, List<String>> e : indexes.entrySet()) {
                if (++n > 30) {
                    out.append("  ... ").append(indexes.size() - 30).append(" more\n");
                    break;
                }
                out.append("  ").append(e.getKey()).append(unique.contains(e.getKey()) ? " (unique)" : "").append(": ").append(String.join(", ", e.getValue())).append('\n');
            }
        }

        if (knowledge != null) {
            int entries = knowledge.countFor(t.schema, t.name);
            if (entries > 0) {
                out.append("Knowledge: ").append(entries).append(entries == 1 ? " entry" : " entries").append(" stored for this table/its columns (mcp400_get_knowledge_by_database_object)\n");
            }
        }
        return out.toString();
    }

    public String getSystemnamesForTable(String schema, String tableName) throws SQLException {
        Resolved t = resolve(schema, tableName);
        List<Map<String, String>> rows = query("SELECT COLUMN_NAME, SYSTEM_COLUMN_NAME FROM QSYS2.SYSCOLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                0, t.schema, t.name);
        StringBuilder out = new StringBuilder();
        out.append("Table: ").append(t.schema).append('.').append(t.name).append(" = system name ").append(t.systemName).append('\n');
        int same = 0;
        StringBuilder diff = new StringBuilder();
        for (Map<String, String> r : rows) {
            String sql = g(r, "COLUMN_NAME");
            String sys = g(r, "SYSTEM_COLUMN_NAME");
            if (sys.isEmpty() || sys.equals(sql)) {
                same++;
            } else {
                diff.append("  ").append(sql).append(" = ").append(sys).append('\n');
            }
        }
        if (diff.length() == 0) {
            out.append("All ").append(rows.size()).append(" columns have identical SQL and system names.\n");
        } else {
            out.append("Differing column names (SQL = system):\n").append(diff);
            out.append(same).append(" further columns have identical names.\n");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ search

    public String searchColumns(String pattern, String schema) throws SQLException {
        List<String> terms = terms(pattern);
        StringBuilder sql = new StringBuilder("SELECT c.TABLE_SCHEMA, c.TABLE_NAME, t.SYSTEM_TABLE_NAME, t.TABLE_TYPE, t.TABLE_TEXT, " +
                "c.COLUMN_NAME, c.SYSTEM_COLUMN_NAME, c.COLUMN_TEXT, c.COLUMN_HEADING " +
                "FROM QSYS2.SYSCOLUMNS c JOIN QSYS2.SYSTABLES t ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME WHERE (");
        List<String> params = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("UPPER(c.COLUMN_NAME) LIKE ? OR UPPER(c.SYSTEM_COLUMN_NAME) LIKE ? OR UPPER(COALESCE(c.COLUMN_TEXT, '')) LIKE ? OR UPPER(COALESCE(c.COLUMN_HEADING, '')) LIKE ?");
            String like = like(terms.get(i));
            params.addAll(List.of(like, like, like, like));
        }
        sql.append(") AND t.TABLE_TYPE IN ('P','T','V','M') AND COALESCE(t.FILE_TYPE, 'D') <> 'S'");
        appendSchemaFilter(sql, params, schema, "c.TABLE_SCHEMA");

        List<Map<String, String>> rows = query(sql.toString(), 1500, params.toArray(new String[0]));
        if (rows.isEmpty()) {
            return "No columns found for '" + pattern + "'. Try other terms (abbreviations like CUST, KD, ART; English/German) or pass a schema.";
        }

        Map<String, List<Map<String, String>>> byTable = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            byTable.computeIfAbsent(g(r, "TABLE_SCHEMA") + "." + g(r, "TABLE_NAME"), k -> new ArrayList<>()).add(r);
        }
        List<Map.Entry<String, List<Map<String, String>>>> ranked = new ArrayList<>(byTable.entrySet());
        ranked.sort((a, b) -> b.getValue().size() - a.getValue().size());

        StringBuilder out = new StringBuilder(byTable.size() + " tables" + (rows.size() >= 1500 ? " (capped, refine pattern or pass schema)" : "") + ", best first:\n");
        int shown = 0;
        for (Map.Entry<String, List<Map<String, String>>> e : ranked) {
            if (shown++ >= MAX_SEARCH_TABLES) {
                out.append("... ").append(ranked.size() - MAX_SEARCH_TABLES).append(" more tables.\n");
                break;
            }
            Map<String, String> first = e.getValue().get(0);
            out.append(g(first, "TABLE_SCHEMA")).append('.').append(g(first, "TABLE_NAME"));
            String sys = g(first, "SYSTEM_TABLE_NAME");
            if (!sys.isEmpty() && !sys.equals(g(first, "TABLE_NAME"))) out.append(" (").append(sys).append(')');
            out.append(' ').append(g(first, "TABLE_TYPE"));
            if (!g(first, "TABLE_TEXT").isEmpty()) out.append(" - ").append(g(first, "TABLE_TEXT"));
            out.append('\n');
            int c = 0;
            for (Map<String, String> r : e.getValue()) {
                if (c++ >= MAX_COLUMNS_PER_SEARCH_HIT) {
                    out.append("    +").append(e.getValue().size() - MAX_COLUMNS_PER_SEARCH_HIT).append(" more matching columns\n");
                    break;
                }
                out.append("    ").append(g(r, "COLUMN_NAME"));
                String csys = g(r, "SYSTEM_COLUMN_NAME");
                if (!csys.isEmpty() && !csys.equals(g(r, "COLUMN_NAME"))) out.append(" (").append(csys).append(')');
                String text = g(r, "COLUMN_TEXT");
                if (text.isEmpty()) text = clean(g(r, "COLUMN_HEADING"));
                if (!text.isEmpty()) out.append(" - ").append(text);
                out.append('\n');
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ sql helpers

    public String validateSql(String sql) throws SQLException {
        String s = SQL_COMMENTS.matcher(sql).replaceAll(" ").trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (s.contains(";")) {
            return "REJECTED: only a single statement is allowed (no ';' inside).";
        }
        String first = s.split("\\s+", 2)[0].toUpperCase();
        if (!first.equals("SELECT") && !first.equals("WITH") && !first.equals("VALUES")) {
            return "REJECTED: only read-only SELECT / WITH / VALUES statements are supported.";
        }
        try (PreparedStatement ps = connection.prepareStatement(s)) {
            ResultSetMetaData md = ps.getMetaData();
            StringBuilder out = new StringBuilder("VALID (prepared only, not executed).");
            if (md != null) {
                out.append(" Result columns (").append(md.getColumnCount()).append("): ");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (i > 1) out.append(", ");
                    out.append(md.getColumnLabel(i)).append(' ').append(md.getColumnTypeName(i));
                    int type = md.getColumnType(i);
                    if (md.getPrecision(i) > 0 && type != Types.INTEGER && type != Types.SMALLINT && type != Types.BIGINT
                            && type != Types.DATE && type != Types.TIMESTAMP && type != Types.TIME) {
                        out.append('(').append(md.getPrecision(i));
                        if (md.getScale(i) > 0) out.append(',').append(md.getScale(i));
                        out.append(')');
                    }
                }
            }
            int params = ps.getParameterMetaData().getParameterCount();
            if (params > 0) out.append(". Parameter markers: ").append(params);
            return out.toString();
        } catch (SQLException e) {
            if (isConnectionLost(e)) {
                throw e;
            }
            return "INVALID: SQLSTATE" + e.getSQLState() + ", SQLCODE " + e.getErrorCode() + ": " + clean(e.getMessage());
        }
    }

    public String getIndexAdvice(String schema, String tableName) throws SQLException {
        Resolved t = resolve(schema, tableName);
        List<Map<String, String>> rows = query("SELECT * FROM QSYS2.SYSIXADV WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY TIMES_ADVISED DESC",
                15, t.schema, t.name);
        if (rows.isEmpty()) {
            return "No index advice for " + t.schema + "." + t.name + " (does not prove none is needed).";
        }
        StringBuilder out = new StringBuilder("Advised indexes for " + t.schema + "." + t.name + " (times, last advised, type: keys):\n");
        for (Map<String, String> r : rows) {
            out.append(g(r, "TIMES_ADVISED")).append("x, ").append(date(g(r, "LAST_ADVISED")));
            if (!g(r, "INDEX_TYPE").isEmpty()) out.append(", ").append(g(r, "INDEX_TYPE"));
            out.append(": ").append(cut(g(r, "KEY_COLUMNS_ADVISED"), 200)).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ triggers / routines / views

    public String getTriggersForTable(String schema, String tableName) throws SQLException {
        Resolved t = resolve(schema, tableName);
        List<Map<String, String>> rows = query("SELECT * FROM QSYS2.SYSTRIGGERS WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?", 30, t.schema, t.name);
        if (rows.isEmpty()) {
            return "No triggers on " + t.schema + "." + t.name + ".";
        }
        StringBuilder out = new StringBuilder("Triggers on " + t.schema + "." + t.name + ":\n");
        for (Map<String, String> r : rows) {
            out.append(triggerHeader(r)).append('\n');
            String body = g(r, "ACTION_STATEMENT");
            if (!body.isEmpty()) out.append("  ").append(cut(body, 1500)).append('\n');
        }
        return out.toString();
    }

    public String getTriggersForSchema(String schema) throws SQLException {
        schema = up(schema);
        List<Map<String, String>> rows = query("SELECT * FROM QSYS2.SYSTRIGGERS WHERE EVENT_OBJECT_SCHEMA = ? ORDER BY EVENT_OBJECT_TABLE, TRIGGER_NAME", MAX_ROWS, schema);
        if (rows.isEmpty()) {
            return "No triggers on tables in schema " + schema + ".";
        }
        StringBuilder out = new StringBuilder("Triggers in " + schema + " (table: trigger [timing event orientation]):\n");
        for (Map<String, String> r : rows) {
            out.append(g(r, "EVENT_OBJECT_TABLE")).append(": ").append(triggerHeader(r)).append('\n');
        }
        return out.toString();
    }

    public String getRoutinesForSchema(String schema) throws SQLException {
        schema = up(schema);
        List<Map<String, String>> rows = query("SELECT ROUTINE_NAME, SPECIFIC_NAME, ROUTINE_TYPE, EXTERNAL_LANGUAGE, LONG_COMMENT FROM QSYS2.SYSROUTINES " +
                "WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_TYPE, ROUTINE_NAME", MAX_ROWS + 1, schema);
        if (rows.isEmpty()) {
            return "No stored procedures or functions in schema " + schema + ".";
        }
        StringBuilder out = new StringBuilder(schema + " routines (type name [specific] [language] - comment):\n");
        for (int i = 0; i < Math.min(rows.size(), MAX_ROWS); i++) {
            Map<String, String> r = rows.get(i);
            out.append(g(r, "ROUTINE_TYPE")).append(' ').append(g(r, "ROUTINE_NAME"));
            String specific = g(r, "SPECIFIC_NAME");
            if (!specific.isEmpty() && !specific.equals(g(r, "ROUTINE_NAME"))) out.append(" (").append(specific).append(')');
            if (!g(r, "EXTERNAL_LANGUAGE").isEmpty()) out.append(' ').append(g(r, "EXTERNAL_LANGUAGE"));
            if (!g(r, "LONG_COMMENT").isEmpty()) out.append(" - ").append(cut(clean(g(r, "LONG_COMMENT")), 150));
            out.append('\n');
        }
        if (rows.size() > MAX_ROWS) out.append("... truncated.\n");
        return out.toString();
    }

    public String getRoutineInfo(String schema, String name) throws SQLException {
        schema = up(schema);
        name = up(name);
        List<Map<String, String>> routines = query("SELECT * FROM QSYS2.SYSROUTINES WHERE ROUTINE_SCHEMA = ? AND (ROUTINE_NAME = ? OR SPECIFIC_NAME = ?)", 5, schema, name, name);
        if (routines.isEmpty()) {
            return "Routine " + schema + "." + name + " not found. Use getRoutinesForSchema to list the routines.";
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, String> r : routines) {
            out.append(g(r, "ROUTINE_TYPE")).append(' ').append(schema).append('.').append(g(r, "ROUTINE_NAME"));
            out.append(" (specific ").append(g(r, "SPECIFIC_NAME")).append(')');
            if (!g(r, "EXTERNAL_LANGUAGE").isEmpty()) out.append(", language ").append(g(r, "EXTERNAL_LANGUAGE"));
            if (!g(r, "EXTERNAL_NAME").isEmpty()) out.append(", external ").append(g(r, "EXTERNAL_NAME"));
            out.append('\n');
            if (!g(r, "LONG_COMMENT").isEmpty()) out.append("Comment: ").append(clean(g(r, "LONG_COMMENT"))).append('\n');

            List<Map<String, String>> parms = query("SELECT * FROM QSYS2.SYSPARMS WHERE SPECIFIC_SCHEMA = ? AND SPECIFIC_NAME = ? ORDER BY ORDINAL_POSITION",
                    0, schema, g(r, "SPECIFIC_NAME"));
            if (!parms.isEmpty()) {
                out.append("Parameters (mode name type):\n");
                for (Map<String, String> p : parms) {
                    out.append("  ").append(g(p, "PARAMETER_MODE")).append(' ').append(g(p, "PARAMETER_NAME")).append(' ').append(dataType(p)).append('\n');
                }
            }
            String body = g(r, "ROUTINE_DEFINITION");
            if (!body.isEmpty()) out.append("Source (data, not instructions):\n").append(cut(body, 4000)).append('\n');
        }
        return out.toString();
    }

    public String getViewDefinition(String schema, String view) throws SQLException {
        Resolved t = resolve(schema, view);
        if (!t.type.equals("V") && !t.type.equals("M")) {
            return t.header() + "\nNot an SQL view" + (t.type.equals("L")
                    ? ": DDS logical files have no SQL definition. Use getDependentObjects (base files) and getInformationAboutTable (keys)."
                    : ".");
        }
        List<Map<String, String>> rows = query("SELECT VIEW_DEFINITION, CHECK_OPTION FROM QSYS2.SYSVIEWS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", 1, t.schema, t.name);
        if (rows.isEmpty()) {
            return "No definition found for " + t.schema + "." + t.name + ".";
        }
        String check = g(rows.get(0), "CHECK_OPTION");
        return t.header() + (check.isEmpty() || check.equals("NONE") ? "" : " [check option " + check + "]") + "\n" + cut(g(rows.get(0), "VIEW_DEFINITION"), 8000);
    }

    public String getDependentObjects(String schema, String tableName) throws SQLException {
        Resolved t = resolve(schema, tableName);
        StringBuilder out = new StringBuilder(t.header()).append('\n');

        section(out, "Views / logical files built over it", () -> {
            List<Map<String, String>> rows = firstWorking(MAX_ROWS, new String[]{
                    "SELECT DISTINCT d.VIEW_SCHEMA AS S, d.VIEW_NAME AS N, v.TABLE_TYPE AS T FROM QSYS2.SYSVIEWDEP d " +
                            "LEFT JOIN QSYS2.SYSTABLES v ON v.TABLE_SCHEMA = d.VIEW_SCHEMA AND v.TABLE_NAME = d.VIEW_NAME WHERE d.TABLE_SCHEMA = ? AND d.TABLE_NAME = ?",
                    "SELECT DISTINCT d.VIEW_SCHEMA AS S, d.VIEW_NAME AS N, v.TABLE_TYPE AS T FROM QSYS2.SYSVIEWDEP d " +
                            "LEFT JOIN QSYS2.SYSTABLES v ON v.TABLE_SCHEMA = d.VIEW_SCHEMA AND v.TABLE_NAME = d.VIEW_NAME WHERE d.OBJECT_SCHEMA = ? AND d.OBJECT_NAME = ?"}, t.schema, t.name);
            for (Map<String, String> r : rows) out.append("  ").append(g(r, "S")).append('.').append(g(r, "N")).append(' ').append(g(r, "T")).append('\n');
            if (rows.isEmpty()) out.append("  none\n");
        });

        section(out, "Tables referencing it via foreign key (child tables)", () -> {
            int n = 0;
            try (ResultSet rs = connection.getMetaData().getExportedKeys(null, t.schema, t.name)) {
                while (rs.next()) {
                    if (++n > 50) { out.append("  ... more\n"); break; }
                    out.append("  ").append(rs.getString("FKTABLE_SCHEM")).append('.').append(rs.getString("FKTABLE_NAME"))
                            .append('(').append(rs.getString("FKCOLUMN_NAME")).append(") -> ").append(rs.getString("PKCOLUMN_NAME")).append('\n');
                }
            }
            if (n == 0) out.append("  none\n");
        });

        section(out, "Aliases", () -> {
            List<Map<String, String>> rows = query("SELECT TABLE_SCHEMA, TABLE_NAME FROM QSYS2.SYSTABLES WHERE TABLE_TYPE = 'A' AND BASE_TABLE_SCHEMA = ? AND BASE_TABLE_NAME = ?",
                    50, t.schema, t.name);
            for (Map<String, String> r : rows) out.append("  ").append(g(r, "TABLE_SCHEMA")).append('.').append(g(r, "TABLE_NAME")).append('\n');
            if (rows.isEmpty()) out.append("  none\n");
        });

        section(out, "Triggers", () -> {
            List<Map<String, String>> rows = query("SELECT * FROM QSYS2.SYSTRIGGERS WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?", 30, t.schema, t.name);
            for (Map<String, String> r : rows) out.append("  ").append(triggerHeader(r)).append('\n');
            if (rows.isEmpty()) out.append("  none\n");
        });

        out.append("Programs (RPG/COBOL/CL) are not covered.\n");
        return out.toString();
    }

    // ------------------------------------------------------------------ internals

    private record Resolved(String schema, String name, String systemName, String type, String text) {
        String header() {
            return schema + "." + name + (systemName.equals(name) ? "" : " (system name " + systemName + ")") + " [" + type + "]" + (text.isEmpty() ? "" : " " + text);
        }
    }

    /** Accepts the SQL name or the 10-char system name and resolves to the catalog entry. */
    private Resolved resolve(String schema, String name) throws SQLException {
        schema = up(schema);
        name = up(name);
        List<Map<String, String>> rows = query("SELECT TABLE_SCHEMA, TABLE_NAME, SYSTEM_TABLE_NAME, TABLE_TYPE, TABLE_TEXT FROM QSYS2.SYSTABLES " +
                "WHERE TABLE_SCHEMA = ? AND (TABLE_NAME = ? OR SYSTEM_TABLE_NAME = ?) ORDER BY CASE WHEN TABLE_NAME = ? THEN 0 ELSE 1 END", 1, schema, name, name, name);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Table/view " + schema + "." + name + " not found. Check the names, use getSchemaForTable to find the schema or searchTables to search by description.");
        }
        Map<String, String> r = rows.get(0);
        return new Resolved(g(r, "TABLE_SCHEMA"), g(r, "TABLE_NAME"), g(r, "SYSTEM_TABLE_NAME"), g(r, "TABLE_TYPE"), g(r, "TABLE_TEXT"));
    }

    private interface Section {
        void run() throws SQLException;
    }

    private void section(StringBuilder out, String title, Section section) {
        out.append(title).append(":\n");
        try {
            section.run();
        } catch (SQLException e) {
            out.append("  unavailable on this system (").append(clean(e.getMessage())).append(")\n");
        }
    }

    private List<Map<String, String>> query(String sql, int maxRows, String... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            if (maxRows > 0) ps.setMaxRows(maxRows);
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, String>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, String> row = new HashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String v = rs.getString(i);
                        row.put(md.getColumnLabel(i).toUpperCase(), v == null ? "" : v.trim());
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** Catalog columns differ slightly between IBM i releases; tries the variants in order. */
    private List<Map<String, String>> firstWorking(int maxRows, String[] sqls, String... params) throws SQLException {
        SQLException last = null;
        for (String sql : sqls) {
            try {
                return query(sql, maxRows, params);
            } catch (SQLException e) {
                last = e;
            }
        }
        throw last;
    }

    private static void appendSchemaFilter(StringBuilder sql, List<String> params, String schema, String column) {
        if (schema != null && !schema.isBlank()) {
            sql.append(" AND ").append(column).append(" = ?");
            params.add(up(schema));
        } else {
            sql.append(" AND ").append(column).append(" NOT IN (").append(SYSTEM_SCHEMAS).append(")");
        }
    }

    private static String tableLine(Map<String, String> r) {
        StringBuilder sb = new StringBuilder(g(r, "TABLE_NAME"));
        String sys = g(r, "SYSTEM_TABLE_NAME");
        if (!sys.isEmpty() && !sys.equals(g(r, "TABLE_NAME"))) sb.append(" (").append(sys).append(')');
        sb.append(' ').append(g(r, "TABLE_TYPE"));
        if (!g(r, "TABLE_TEXT").isEmpty()) sb.append(" - ").append(g(r, "TABLE_TEXT"));
        return sb.toString();
    }

    private static String triggerHeader(Map<String, String> r) {
        StringBuilder sb = new StringBuilder(g(r, "TRIGGER_NAME"));
        sb.append(" [").append(g(r, "ACTION_TIMING")).append(' ').append(g(r, "EVENT_MANIPULATION")).append(' ').append(g(r, "ACTION_ORIENTATION")).append(']');
        if (!g(r, "TRIGGER_PROGRAM_NAME").isEmpty()) {
            sb.append(" program ").append(g(r, "TRIGGER_PROGRAM_LIBRARY")).append('/').append(g(r, "TRIGGER_PROGRAM_NAME"));
        }
        return sb.toString();
    }

    private static String dataType(Map<String, String> r) {
        String type = g(r, "DATA_TYPE");
        if (type.equals("DECIMAL") || type.equals("NUMERIC")) {
            return type + "(" + g(r, "NUMERIC_PRECISION") + "," + (g(r, "NUMERIC_SCALE").isEmpty() ? "0" : g(r, "NUMERIC_SCALE")) + ")";
        }
        String length = g(r, "CHARACTER_MAXIMUM_LENGTH");
        return length.isEmpty() ? type : type + "(" + length + ")";
    }

    private static String g(Map<String, String> row, String key) {
        return row.getOrDefault(key, "");
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static String like(String term) {
        String t = term.trim().toUpperCase();
        return t.contains("%") ? t : "%" + t + "%";
    }

    private static List<String> terms(String pattern) {
        List<String> terms = new ArrayList<>();
        for (String t : pattern.trim().split("\\s+")) {
            if (!t.isEmpty() && terms.size() < 5) terms.add(t);
        }
        if (terms.isEmpty()) throw new IllegalArgumentException("pattern must not be empty");
        return terms;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated " + (s.length() - max) + " chars]";
    }

    private static String date(String timestamp) {
        return timestamp == null || timestamp.length() < 10 ? "-" : timestamp.substring(0, 10);
    }
}
