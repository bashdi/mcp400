package com.github.bashdi.mcp400.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** Builds MCP tools from a name, a description, parameters and a handler; shared by all capabilities. */
public final class McpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpTools() {
    }

    public record Param(String name, String description, boolean required, String type, List<String> allowed) {
        public Param(String name, String description, boolean required) {
            this(name, description, required, "string", null);
        }
    }

    public interface ToolHandler {
        String call(Map<String, Object> arguments) throws Exception;
    }

    public static String str(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }

    public static Long lng(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString().trim());
    }

    /** Runs the tool; if the connection to the IBM i is gone, reconnects once and retries. */
    private static String callWithReconnect(ToolHandler handler, Map<String, Object> arguments) throws Exception {
        try {
            return handler.call(arguments);
        } catch (Exception e) {
            if (!As400Connection.isConnectionLost(e)) throw e;
            As400Connection.reconnectAll();
            return handler.call(arguments);
        }
    }

    public static SyncToolSpecification tool(String name, String description, List<Param> params, ToolHandler handler) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (Param p : params) {
            ObjectNode prop = properties.putObject(p.name()).put("type", p.type()).put("description", p.description());
            if (p.allowed() != null) {
                var values = prop.putArray("enum");
                p.allowed().forEach(values::add);
            }
            if (p.required()) required.add(p.name());
        }
        return new SyncToolSpecification(new Tool(name, description, schema.toString()), (exchange, arguments) -> {
            try {
                for (Param p : params) {
                    if (p.required() && (arguments.get(p.name()) == null || arguments.get(p.name()).toString().isBlank())) {
                        throw new IllegalArgumentException("Missing argument: " + p.name());
                    }
                }
                return new CallToolResult(List.of(new TextContent(callWithReconnect(handler, arguments))), false);
            } catch (Exception e) {
                return new CallToolResult(List.of(new TextContent("Error: " + e.getMessage())), true);
            }
        });
    }
}
