package com.github.bashdi.mcp400.core;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;

/** A group of MCP tools that a server can offer, e.g. database or programming tools. */
public interface Capability {

    /** Instructions for the agent, sent once per server; empty if there are none. */
    String instructions();

    List<SyncToolSpecification> tools();
}
