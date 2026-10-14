package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.As400Connection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Who uses an object: every program and service program of the library is run through DSPPGMREF,
 * which writes its references to an outfile in QTEMP. The outfile is read per program and removed at the end.
 */
public final class ReferenceSearch {

    private static final int MAX_PROGRAMS = 100;
    private static final String OUTFILE = "MCPREF";

    private ReferenceSearch() {
    }

    public static String find(String library, String name) throws Exception {
        String lib = IbmiSource.validName(library, "library");
        String object = IbmiSource.validName(name, "name");
        List<String> programs = programs(lib);

        List<String> out = new ArrayList<>();
        out.add("references to " + lib + "/" + object);
        out.add("programs and service programs in " + lib + ": " + programs.size()
                + (programs.size() > MAX_PROGRAMS ? ", first " + MAX_PROGRAMS + " checked" : ""));

        List<String> users = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            try {
                for (String program : programs.subList(0, Math.min(programs.size(), MAX_PROGRAMS))) {
                    try {
                        CompileService.runCommand(c, "DSPPGMREF PGM(" + lib + "/" + program + ") OBJTYPE(*ALL) OUTPUT(*OUTFILE) "
                                + "OUTFILE(QTEMP/" + OUTFILE + ") OUTMBR(*FIRST *REPLACE)");
                        users.addAll(usersOf(c, lib, program, object));
                    } catch (SQLException e) {
                        failed.add("  " + lib + "/" + program + ": " + (e.getMessage() == null ? "error" : e.getMessage()));
                    }
                }
            } finally {
                try {
                    CompileService.runCommand(c, "DLTF FILE(QTEMP/" + OUTFILE + ")");
                } catch (SQLException ignored) {
                    // outfile was never created
                }
            }
        }

        out.add("used by:");
        out.addAll(users.isEmpty() ? List.of("  no program or service program of " + lib + " references " + object) : users);
        if (!failed.isEmpty()) {
            out.add("not checked (DSPPGMREF failed):");
            out.addAll(failed);
        }
        return String.join("\n", out);
    }

    /** Names of the programs and service programs of the library. */
    private static List<String> programs(String lib) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT OBJNAME FROM TABLE(QSYS2.OBJECT_STATISTICS(?, '*ALL')) X " +
                    "WHERE OBJTYPE IN ('*PGM', '*SRVPGM') ORDER BY OBJNAME")) {
                ps.setString(1, lib);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1).trim());
                    }
                }
            }
        }
        return names;
    }

    /**
     * The references of one program in the outfile that point to the object.
     * Outfile fields: WHFNAM referenced object, WHLNAM its library, WHOTYP its type.
     */
    private static List<String> usersOf(Connection c, String lib, String program, String object) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT WHLNAM, WHOTYP FROM QTEMP." + OUTFILE + " WHERE WHFNAM = ? AND WHLNAM = ?")) {
            ps.setString(1, object);
            ps.setString(2, lib);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add("  " + lib + "/" + program + " uses " + rs.getString(1).trim() + "/" + object + " " + rs.getString(2).trim());
                }
            }
        }
        return lines;
    }
}
