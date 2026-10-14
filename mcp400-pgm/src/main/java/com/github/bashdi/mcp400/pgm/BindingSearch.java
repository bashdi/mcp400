package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.As400Connection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Entries of binding directories (*BNDDIR) and the procedures exported by the service programs they bind.
 * Read-only, one line per result. Binding directories themselves are found with search_objects (*BNDDIR).
 */
public final class BindingSearch {

    private static final int MAX_ROWS = 200;
    private static final Pattern LIBRARY = Pattern.compile("[A-Z#@$][A-Z0-9#@$_]{0,9}");
    /** Procedure names are case sensitive in C, so the pattern is not upper-cased. */
    private static final Pattern PROCEDURE = Pattern.compile("[A-Za-z0-9_#@$*]{1,128}");

    private BindingSearch() {
    }

    /** The entries (service programs and modules) of one binding directory. */
    public static String entries(String library, String directory) throws SQLException {
        String lib = validLibrary(library, "library");
        String dir = validLibrary(directory, "directory");
        List<String> lines = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT OBJECT_LIBRARY, OBJECT_NAME FROM QSYS2.BINDING_DIRECTORY_INFO " +
                    "WHERE BINDING_DIRECTORY_LIBRARY = ? AND BINDING_DIRECTORY_NAME = ? ORDER BY OBJECT_NAME FETCH FIRST " + (MAX_ROWS + 1) + " ROWS ONLY")) {
                ps.setString(1, lib);
                ps.setString(2, dir);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString(1).trim() + "/" + rs.getString(2).trim());
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("binding directory: " + lib + "/" + dir);
        if (lines.isEmpty()) {
            out.add("no entries (or the directory does not exist)");
            return String.join("\n", out);
        }
        out.add("entries: " + Math.min(lines.size(), MAX_ROWS) + (lines.size() > MAX_ROWS ? "+" : ""));
        out.addAll(lines.subList(0, Math.min(lines.size(), MAX_ROWS)));
        if (lines.size() > MAX_ROWS) {
            out.add("... more entries omitted");
        }
        return String.join("\n", out);
    }

    /**
     * Procedures exported by service programs whose name matches the pattern (* wildcard).
     * With a binding directory, only service programs listed in that directory are searched.
     */
    public static String procedures(String pattern, String library, String directory) throws SQLException {
        if (pattern == null || !PROCEDURE.matcher(pattern.trim()).matches()) {
            throw new IllegalArgumentException("pattern fehlt oder ungültig (erlaubt: Buchstaben, Ziffern, _ # @ $ und * als Platzhalter)");
        }
        String like = pattern.trim().replace('*', '%');
        String lib = library == null || library.isBlank() ? null : validLibrary(library, "library");
        String dir = directory == null || directory.isBlank() ? null : validLibrary(directory, "binding_directory");
        if (dir != null && lib == null) {
            throw new IllegalArgumentException("binding_directory braucht library");
        }

        StringBuilder sql = new StringBuilder("SELECT PROGRAM_LIBRARY, PROGRAM_NAME, SYMBOL_NAME FROM QSYS2.PROGRAM_EXPORT_IMPORT_INFO " +
                "WHERE OBJECT_TYPE = '*SRVPGM' AND SYMBOL_NAME LIKE ?");
        if (dir != null) {
            sql.append(" AND PROGRAM_NAME IN (SELECT OBJECT_NAME FROM QSYS2.BINDING_DIRECTORY_INFO " +
                    "WHERE BINDING_DIRECTORY_LIBRARY = ? AND BINDING_DIRECTORY_NAME = ?)");
        }
        sql.append(" ORDER BY SYMBOL_NAME FETCH FIRST ").append(MAX_ROWS + 1).append(" ROWS ONLY");

        List<String> lines = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int i = 1;
                ps.setString(i++, like);
                if (dir != null) {
                    ps.setString(i++, lib);
                    ps.setString(i, dir);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString(3).trim() + " - " + rs.getString(1).trim() + "/" + rs.getString(2).trim());
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("procedures matching " + pattern.trim() + (dir == null ? "" : " in " + lib + "/" + dir));
        if (lines.isEmpty()) {
            out.add("none found");
            return String.join("\n", out);
        }
        out.add("found: " + Math.min(lines.size(), MAX_ROWS) + (lines.size() > MAX_ROWS ? "+" : ""));
        out.addAll(lines.subList(0, Math.min(lines.size(), MAX_ROWS)));
        if (lines.size() > MAX_ROWS) {
            out.add("... more, narrow the pattern");
        }
        return String.join("\n", out);
    }

    private static String validLibrary(String name, String field) {
        String value = name == null ? "" : name.trim().toUpperCase();
        if (!LIBRARY.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " fehlt oder ist kein gültiger IBM-i-Name");
        }
        return value;
    }
}
