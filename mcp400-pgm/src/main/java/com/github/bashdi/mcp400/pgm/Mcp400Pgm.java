package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.ServerLauncher;

import java.util.List;

/** Entry point of mcp400-pgm: the programming capabilities only. */
public final class Mcp400Pgm {

    private Mcp400Pgm() {
    }

    public static void main(String[] args) throws Exception {
        ServerLauncher.run("mcp400-pgm", List.of(new PgmCapability()));
    }
}
