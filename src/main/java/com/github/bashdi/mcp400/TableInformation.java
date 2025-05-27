package com.github.bashdi.mcp400;

import java.util.List;

public record TableInformation(List<Column> columns, List<String> primaryKeyColumns, List<Index> indices) {


}
