package com.github.bashdi.mcp400;

import com.github.bashdi.mcp400.core.ServerLauncher;
import com.github.bashdi.mcp400.db.DbCapability;
import com.github.bashdi.mcp400.pgm.PgmCapability;

import java.util.List;

/** Entry point of mcp400: all capabilities (database and programming). */
public final class Mcp400 {

    private Mcp400() {
    }

    public static void main(String[] args) throws Exception {
        ServerLauncher.run("mcp400", List.of(new DbCapability(), new PgmCapability()));
    }
}
