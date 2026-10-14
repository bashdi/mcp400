package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.As400Connection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Searches the objects of one IBM i library by name pattern and object type. Read-only, one line per object. */
public final class ObjectSearch {

    private static final int MAX_ROWS = 200;
    private static final int MAX_TEXT_CHARS = 60;
    private static final Set<String> OBJECT_TYPES = Set.of("*ALL", "*PGM", "*SRVPGM", "*FILE", "*MODULE", "*DTAARA", "*DTAQ", "*MSGF", "*OUTQ", "*BNDDIR");
    private static final Pattern LIBRARY = Pattern.compile("[A-Z#@$][A-Z0-9#@$_]{0,9}");
    /** Object name with * (any characters) as wildcard. */
    private static final Pattern NAME = Pattern.compile("[A-Z0-9#@$_*]{1,10}");

    private ObjectSearch() {
    }

    public static String search(String library, String name, String objectType) throws SQLException {
        String lib = upper(library);
        if (lib.isEmpty() || !LIBRARY.matcher(lib).matches()) {
            throw new IllegalArgumentException("library fehlt oder ist kein gültiger IBM-i-Bibliotheksname");
        }
        String type = upper(objectType).isEmpty() ? "*ALL" : upper(objectType);
        if (!OBJECT_TYPES.contains(type)) {
            throw new IllegalArgumentException("object_type '" + type + "' nicht unterstützt (erlaubt: " + String.join(", ", OBJECT_TYPES.stream().sorted().toList()) + ")");
        }
        String pattern = upper(name).isEmpty() ? "*" : upper(name);
        if (!NAME.matcher(pattern).matches()) {
            throw new IllegalArgumentException("name '" + name + "' ungültig (erlaubt: A-Z, 0-9, # @ $ _ und * als Platzhalter, max. 10 Zeichen)");
        }

        List<String> lines = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT OBJNAME, OBJTYPE, OBJATTRIBUTE, OBJTEXT FROM TABLE(QSYS2.OBJECT_STATISTICS(?, ?)) X " +
                    "WHERE OBJNAME LIKE ? ORDER BY OBJTYPE, OBJNAME FETCH FIRST " + (MAX_ROWS + 1) + " ROWS ONLY")) {
                ps.setString(1, lib);
                ps.setString(2, type);
                ps.setString(3, pattern.replace('*', '%'));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    }
                }
            }
        }

        List<String> out = new ArrayList<>();
        out.add("library: " + lib + ", type: " + type + ", name: " + pattern);
        if (lines.isEmpty()) {
            out.add("no objects found. Check the library name, or use another name pattern or object_type.");
            return String.join("\n", out);
        }
        out.add("found: " + Math.min(lines.size(), MAX_ROWS) + (lines.size() > MAX_ROWS ? "+" : "") + " objects");
        out.addAll(lines.subList(0, Math.min(lines.size(), MAX_ROWS)));
        if (lines.size() > MAX_ROWS) {
            out.add("... more, narrow the name pattern or set object_type");
        }
        return String.join("\n", out);
    }

    /** Description of the object(s) with exactly this name: type, attribute, owner and text. */
    public static String describe(String library, String name, String objectType) throws SQLException {
        String lib = IbmiSource.validName(library, "library");
        String object = IbmiSource.validName(name, "name");
        String type = upper(objectType).isEmpty() ? "*ALL" : upper(objectType);
        if (!OBJECT_TYPES.contains(type)) {
            throw new IllegalArgumentException("object_type '" + type + "' nicht unterstützt");
        }
        List<String> out = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT OBJNAME, OBJTYPE, OBJATTRIBUTE, OBJTEXT, OBJOWNER FROM TABLE(QSYS2.OBJECT_STATISTICS(?, ?)) X WHERE OBJNAME = ?")) {
                ps.setString(1, lib);
                ps.setString(2, type);
                ps.setString(3, object);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String text = rs.getString(4) == null ? "" : rs.getString(4).replaceAll("\\s+", " ").trim();
                        out.add(lib + "/" + rs.getString(1).trim() + " " + rs.getString(2).trim()
                                + (rs.getString(3) == null || rs.getString(3).isBlank() ? "" : " " + rs.getString(3).trim())
                                + " owner: " + (rs.getString(5) == null ? "-" : rs.getString(5).trim()));
                        if (!text.isEmpty()) out.add("  text: " + text);
                    }
                }
            }
        }
        if (out.isEmpty()) {
            return "object " + lib + "/" + object + " not found" + (type.equals("*ALL") ? "" : " as " + type);
        }
        return String.join("\n", out);
    }

    private static String line(String name, String type, String attribute, String text) {
        String description = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (description.length() > MAX_TEXT_CHARS) {
            description = description.substring(0, MAX_TEXT_CHARS) + "...";
        }
        String result = name.trim() + " " + type.trim();
        if (attribute != null && !attribute.isBlank()) {
            result += " " + attribute.trim();
        }
        return description.isEmpty() ? result : result + " - " + description;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
