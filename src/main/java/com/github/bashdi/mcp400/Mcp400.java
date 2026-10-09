package com.github.bashdi.mcp400;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class Mcp400 {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String INSTRUCTIONS = """
			Metadata server for Db2 for i (IBM i / AS400). It only reads the system catalog (QSYS2), never table data.
			All results are compact plain text. Type legend: T=SQL table, P=DDS physical file, L=DDS logical file, V=view, A=alias, M=materialized query table.

			Recommended workflow:
			1. Find tables: legacy table/column names are often cryptic (e.g. KDNR, ARTST). Use searchColumns / searchTables with business terms,
			   abbreviations and synonyms (descriptions and column headings are searched too). Several space separated terms are OR-combined.
			2. Understand them: getAllColumnsForTable (columns, types, descriptions), getInformationAboutTable (type, size, keys, indexes, constraints),
			   getDependentObjects / getViewDefinition for views and logical files.
			3. Write the SQL, then check it with validateSql (prepare only, nothing is executed). Use getIndexAdvice to see what the optimizer is missing.

			Db2 for i specifics for writing good SQL:
			- Every table/column has a long SQL name and a 10 character system name (DDS files usually only have the system name). SQL accepts both; CL/RPG/DDS use the system name.
			- Qualify tables as SCHEMA.TABLE. A schema is an IBM i library. Unqualified names are resolved through the job's library list.
			- Limit rows with FETCH FIRST n ROWS ONLY (no LIMIT); add OPTIMIZE FOR n ROWS for interactive queries. Use WITH UR to avoid record locks when reading.
			- Select only the needed columns (no SELECT *). Filter on the LEADING key columns of an index (see getInformationAboutTable) and do not wrap indexed columns in functions.
			- Join along foreign keys; if there are none, join on columns with identical name and type (check getAllColumnsForTable). Mismatching types/lengths in a join prevent index use.
			- NUMERIC = zoned decimal, DECIMAL = packed decimal. Legacy dates are frequently stored as DECIMAL/NUMERIC/CHAR (YYYYMMDD or CYYMMDD with century digit 0/1), check the column description before treating a column as DATE.
			- CHAR columns are blank padded (trailing blanks are ignored in comparisons); use RTRIM only for output. Columns with ccsid=65535 are not converted: cast e.g. CAST(col AS CHAR(n) CCSID 37).
			- Logical files (L) are alternate access paths: query the physical file unless the select/omit logic of the logical file is needed. Views (V) may contain joins/filters, check getViewDefinition.
			- Unquoted names are upper case. A file with several members is read via its first member unless an alias on the member is used.
			""";

	public static void main(String[] args) throws Exception {
		MCP400Service s = new MCP400Service();

		Param schema = new Param("schema", "Schema = library name on the IBM i, e.g. MYLIB", true);
		Param table = new Param("table", "Table, view or logical file name. The long SQL name or the 10 character system name both work", true);

		McpServer.sync(new StdioServerTransportProvider(MAPPER))
				.serverInfo("mcp400", "0.0.1")
				.instructions(INSTRUCTIONS)
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.tools(
						tool("mcp400_search_columns",
								"Finds tables by column: searches column names, system names, descriptions and column headings in all user schemas (or one schema). " +
								"Best starting point when table names are cryptic. Returns tables grouped and ranked by number of matching columns, with table description. " +
								"Use abbreviations and synonyms, several terms are OR-combined, % is a wildcard. Example: 'customer kunde KDNR'. Passing a schema makes it much faster.",
								List.of(new Param("pattern", "Search terms separated by spaces, case-insensitive, substring match", true),
										new Param("schema", "Optional: restrict to one schema", false)),
								a -> s.searchColumns(str(a, "pattern"), str(a, "schema"))),
						tool("mcp400_search_tables",
								"Finds tables, views and physical files by name, system name or description text (without logical files and source files) in all user schemas or one schema. " +
								"Use when you know the business term but not the technical table name.",
								List.of(new Param("pattern", "Search terms separated by spaces, case-insensitive, substring match", true),
										new Param("schema", "Optional: restrict to one schema", false)),
								a -> s.searchTables(str(a, "pattern"), str(a, "schema"))),
						tool("mcp400_get_all_tables_for_schema",
								"Lists the tables, views, aliases and physical files of one schema with type, system name and description. Source files are never listed. " +
								"Logical files are hidden by default because they only repeat data of physical files. At most 300 entries; use filter to narrow down.",
								List.of(schema,
										new Param("filter", "Optional: substring (or % pattern) matched against name, system name and description", false),
										new Param("includeLogicals", "Optional: 'true' to also list DDS logical files", false)),
								a -> s.getAllTablesForSchema(str(a, "schema"), str(a, "filter"), "true".equalsIgnoreCase(str(a, "includeLogicals")))),
						tool("mcp400_get_schemas_for_table",
								"Returns all user schemas (libraries) that contain an object with this name (SQL or system name), newest first, with type and description. " +
								"Use it when the same table exists in several libraries (test/production/copies) or the schema is unknown.",
								List.of(new Param("table", "Table or view name (SQL or system name)", true)),
								a -> s.getSchemaForTable(str(a, "table"))),
						tool("mcp400_get_all_columns_for_table",
								"Lists all columns of a table/view/file in order with data type, nullability, default, system name (if different) and description (or column heading). " +
								"Legend: NUMERIC=zoned, DECIMAL=packed decimal, '?' = nullable, ccsid=65535 = binary/not converted.",
								List.of(schema, table),
								a -> s.getAllColumnsForTable(str(a, "schema"), str(a, "table"))),
						tool("mcp400_get_information_about_table",
								"Returns type (T/P/L/V/A/M), description, system name, approximate row count and last change/use date, primary key, foreign keys, check/unique constraints " +
								"and all indexes/keyed logical files with their key columns in order. Use the index keys to decide which WHERE/JOIN columns are fast. " +
								"Does not list columns, use getAllColumnsForTable for that.",
								List.of(schema, table),
								a -> s.getInformationAboutTable(str(a, "schema"), str(a, "table"))),
						tool("mcp400_get_systemnames_for_table",
								"Returns the 10 character system name of the table and the system names of all columns whose SQL name differs. " +
								"Needed when talking to CL/RPG/DDS or when SQL names are long (SQL accepts both names).",
								List.of(schema, table),
								a -> s.getSystemnamesForTable(str(a, "schema"), str(a, "table"))),
						tool("mcp400_get_dependent_objects",
								"Shows what depends on a table: views and logical files built over it, child tables that reference it by foreign key, aliases and triggers. " +
								"Use it for impact analysis and to find the base files of a logical file. Programs (RPG/COBOL/CL) are not covered.",
								List.of(schema, table),
								a -> s.getDependentObjects(str(a, "schema"), str(a, "table"))),
						tool("mcp400_get_view_definition",
								"Returns the SQL source of a view or materialized query table, which reveals joins, filters and business rules. DDS logical files have no SQL definition.",
								List.of(schema, new Param("view", "View name (SQL or system name)", true)),
								a -> s.getViewDefinition(str(a, "schema"), str(a, "view"))),
						tool("mcp400_validate_sql",
								"Checks a SELECT / WITH / VALUES statement by preparing it on the IBM i without executing it: reports syntax errors, unknown tables/columns " +
								"(SQLSTATE/SQLCODE and message) or, if valid, the result columns with types. Only a single read-only statement is accepted. Always use it before presenting SQL.",
								List.of(new Param("sql", "A single SELECT statement", true)),
								a -> s.validateSql(str(a, "sql"))),
						tool("mcp400_get_index_advice",
								"Returns the indexes the Db2 for i optimizer recommended for a table (QSYS2.SYSIXADV), most requested first. " +
								"Helps to see which key columns queries on this table are missing and therefore which filters are slow. Empty result does not prove no index is needed.",
								List.of(schema, table),
								a -> s.getIndexAdvice(str(a, "schema"), str(a, "table"))),
						tool("mcp400_get_triggers_for_table",
								"Lists the triggers on a table with timing, event, orientation and the source of SQL triggers. Triggers can change data or enforce rules outside the application.",
								List.of(schema, table),
								a -> s.getTriggersForTable(str(a, "schema"), str(a, "table"))),
						tool("mcp400_get_triggers_for_schema",
								"Lists all triggers on tables of a schema in one line each (no source). Use getTriggersForTable to see the source.",
								List.of(schema),
								a -> s.getTriggersForSchema(str(a, "schema"))),
						tool("mcp400_get_routines_for_schema",
								"Lists stored procedures and functions of a schema with language and comment. They often encapsulate business logic.",
								List.of(schema),
								a -> s.getRoutinesForSchema(str(a, "schema"))),
						tool("mcp400_get_routine_info",
								"Returns signature (parameters with mode and type), comment and source of a stored procedure or function. Overloaded routines return one block per variant.",
								List.of(schema, new Param("name", "Routine name or specific name", true)),
								a -> s.getRoutineInfo(str(a, "schema"), str(a, "name"))))
				.build();

		// stdio transport runs on its own threads; keep the process alive
		new CountDownLatch(1).await();
	}

	private record Param(String name, String description, boolean required) {
	}

	private interface ToolHandler {
		String call(Map<String, Object> arguments) throws Exception;
	}

	private static String str(Map<String, Object> arguments, String key) {
		Object value = arguments.get(key);
		return value == null ? null : value.toString();
	}

	private static SyncToolSpecification tool(String name, String description, List<Param> params, ToolHandler handler) {
		ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
		ObjectNode properties = schema.putObject("properties");
		var required = schema.putArray("required");
		for (Param p : params) {
			properties.putObject(p.name()).put("type", "string").put("description", p.description());
			if (p.required()) required.add(p.name());
		}
		return new SyncToolSpecification(new Tool(name, description, schema.toString()), (exchange, arguments) -> {
			try {
				for (Param p : params) {
					if (p.required() && (arguments.get(p.name()) == null || arguments.get(p.name()).toString().isBlank())) {
						throw new IllegalArgumentException("Missing argument: " + p.name());
					}
				}
				return new CallToolResult(List.of(new TextContent(handler.call(arguments))), false);
			} catch (Exception e) {
				return new CallToolResult(List.of(new TextContent("Error: " + e.getMessage())), true);
			}
		});
	}
}
