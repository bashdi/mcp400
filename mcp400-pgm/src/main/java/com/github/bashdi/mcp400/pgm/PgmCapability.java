package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.Capability;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;

/** Programming capability. Placeholder: no tools yet. */
public final class PgmCapability implements Capability {

	@Override
	public String instructions() {
		return "";
	}

	@Override
	public List<SyncToolSpecification> tools() {
		return List.of();
	}
}
