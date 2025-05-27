package com.github.bashdi.mcp400;

import java.util.List;

public record Index(String name, List<String> columnsInOrder, boolean isUnique) {
}
