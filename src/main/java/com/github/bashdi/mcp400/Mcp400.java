package com.github.bashdi.mcp400;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

public class Mcp400 {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(String[] args) throws Exception {
		MCP400Service service = new MCP400Service();

		McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(MAPPER))
				.serverInfo("mcp400", "0.0.1")
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.tools(
						tool("mcp400_get_all_tables_for_schema",
								"Gets a list of all tables(name and description) in a specific schema on the database/as400/ibmi",
								"""
								{"type":"object","properties":{"schema":{"type":"string"}},"required":["schema"]}""",
								a -> service.getAllTablesForSchema(str(a, "schema"))),
						tool("mcp400_get_information_about_table",
								"Gets a list of all columns(name, description, datatype), index and primary key by schema and tableName",
								"""
								{"type":"object","properties":{"schema":{"type":"string"},"tableName":{"type":"string"}},"required":["schema","tableName"]}""",
								a -> service.getAllColumnsForTable(str(a, "schema"), str(a, "tableName"))),
						tool("mcp400_get_schemas_for_table",
								"Gets a list of schemas containing the table",
								"""
								{"type":"object","properties":{"tableName":{"type":"string"}},"required":["tableName"]}""",
								a -> service.getSchemaForTable(str(a, "tableName"))))
				.build();

		// stdio transport runs on its own threads; keep the process alive
		new CountDownLatch(1).await();
	}

	private static String str(Map<String, Object> arguments, String key) {
		Object value = arguments.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Missing argument: " + key);
		}
		return value.toString();
	}

	private interface ToolHandler {
		Object call(Map<String, Object> arguments) throws Exception;
	}

	private static SyncToolSpecification tool(String name, String description, String inputSchema, ToolHandler handler) {
		return new SyncToolSpecification(new Tool(name, description, inputSchema), (exchange, arguments) -> {
			try {
				String json = MAPPER.writeValueAsString(handler.call(arguments));
				return new CallToolResult(java.util.List.of(new TextContent(json)), false);
			} catch (Exception e) {
				return new CallToolResult(java.util.List.of(new TextContent("Error: " + e.getMessage())), true);
			}
		});
	}
}
