package com.github.bashdi.mcp400.db;

import com.github.bashdi.mcp400.core.ServerLauncher;

import java.util.List;

/** Entry point of mcp400-db: the database capabilities only. */
public final class Mcp400Db {

    private Mcp400Db() {
    }

    public static void main(String[] args) throws Exception {
        ServerLauncher.run("mcp400-db", List.of(new DbCapability()));
    }
}
