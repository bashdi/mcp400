package com.github.bashdi.mcp400;

public record Column(String name, String description, String datatype, boolean isAutoIncremented) {
}
