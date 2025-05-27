package com.github.bashdi.mcp400;

public record ForeignKey(String foreignSchema, String foreignTable, String foreignColumn, String column) {
}
