package com.github.bashdi.mcp400.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Starts a stdio MCP server that offers the tools of the given capabilities. */
public final class ServerLauncher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ServerLauncher() {
    }

    public static void run(String name, List<Capability> capabilities) throws InterruptedException {
        List<SyncToolSpecification> tools = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        for (Capability capability : capabilities) {
            tools.addAll(capability.tools());
            if (!capability.instructions().isEmpty()) instructions.add(capability.instructions());
        }

        McpServer.sync(new StdioServerTransportProvider(MAPPER))
                .serverInfo(name, version())
                .instructions(String.join("\n", instructions))
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();

        // stdio transport runs on its own threads; keep the process alive
        new CountDownLatch(1).await();
    }

    /** The version from the jar manifest (Implementation-Version, set by the build). */
    private static String version() {
        Package pkg = ServerLauncher.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
