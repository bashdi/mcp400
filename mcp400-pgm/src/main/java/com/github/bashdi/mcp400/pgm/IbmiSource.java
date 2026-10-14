package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.As400Connection;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400File;
import com.ibm.as400.access.AS400FileRecordDescription;
import com.ibm.as400.access.Record;
import com.ibm.as400.access.RecordFormat;
import com.ibm.as400.access.SequentialFile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Source members of IBM i source physical files (QRPGLESRC, QCLLESRC, ...): the member list and text search.
 * The members are read through the record interface of jt400, in a job of their own; no temporary objects are created.
 */
public final class IbmiSource {

    static final int MAX_HITS = 100;
    static final int MAX_MEMBERS = 500;
    private static final int MAX_LINE_CHARS = 150;
    private static final Pattern NAME = Pattern.compile("[A-Z#@$][A-Z0-9#@$_]{0,9}");

    /** Result of a scan: how many members were read and the hits as "MEMBER:LINE text". */
    public record Scan(int members, List<String> hits) {
    }

    private IbmiSource() {
    }

    /** The members of one source file. */
    public static String listMembers(String library, String file) throws SQLException {
        String lib = validName(library, "library");
        String src = validName(file, "file");
        List<String> members = memberNames(lib, src);
        List<String> out = new ArrayList<>();
        out.add("source file: " + lib + "/" + src);
        if (members.isEmpty()) {
            out.add("no members (or the file does not exist)");
            return String.join("\n", out);
        }
        out.add("members: " + Math.min(members.size(), MAX_MEMBERS) + (members.size() > MAX_MEMBERS ? "+" : ""));
        out.addAll(members.subList(0, Math.min(members.size(), MAX_MEMBERS)));
        return String.join("\n", out);
    }

    /**
     * Lines containing the text (case-insensitive) in the members of a source file.
     * With member, only that member; without member, all members of the file.
     */
    public static String search(String library, String file, String member, String text) throws Exception {
        String lib = validName(library, "library");
        String src = validName(file, "file");
        String mbr = member == null || member.isBlank() ? null : validName(member, "member");
        Scan scan = scan(lib, src, mbr, text);

        List<String> out = new ArrayList<>();
        out.add("search in " + lib + "/" + src + (mbr == null ? "" : "(" + mbr + ")") + " for '" + text.trim() + "'");
        out.add("members scanned: " + scan.members());
        if (scan.hits().isEmpty()) {
            out.add("no matches");
            return String.join("\n", out);
        }
        out.add("hits: " + scan.hits().size() + (scan.hits().size() >= MAX_HITS ? "+ (max. " + MAX_HITS + ")" : ""));
        out.addAll(scan.hits());
        return String.join("\n", out);
    }

    /** Scans the members (one, or all of the file) for the text, case-insensitive. */
    public static Scan scan(String lib, String src, String mbr, String text) throws Exception {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text fehlt");
        }
        String needle = text.trim().toUpperCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        AS400 system = As400Connection.system();
        try {
            List<String> members = mbr != null ? List.of(mbr) : memberNames(lib, src);
            for (String name : members) {
                if (scanned >= MAX_MEMBERS || hits.size() >= MAX_HITS) break;
                scanned++;
                try {
                    List<String> lines = readMember(system, lib, src, name);
                    for (int i = 0; i < lines.size() && hits.size() < MAX_HITS; i++) {
                        String line = lines.get(i);
                        if (line.toUpperCase(Locale.ROOT).contains(needle)) {
                            hits.add(name + ":" + (i + 1) + " " + cut(line.strip()));
                        }
                    }
                } catch (Exception e) {
                    hits.add(name + ": nicht lesbar (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
                }
            }
        } finally {
            system.disconnectAllServices();
        }
        return new Scan(scanned, List.copyOf(hits));
    }

    private static List<String> memberNames(String lib, String src) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_PARTITION FROM QSYS2.SYSPARTITIONSTAT " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY TABLE_PARTITION FETCH FIRST " + (MAX_MEMBERS + 1) + " ROWS ONLY")) {
                ps.setString(1, lib);
                ps.setString(2, src);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1).trim());
                    }
                }
            }
        }
        return names;
    }

    /** The source lines of one member; the record interface reads SRCDTA of each record. */
    static List<String> readMember(AS400 system, String lib, String file, String member) throws Exception {
        String path = "/QSYS.LIB/" + lib + ".LIB/" + file + ".FILE/" + member + ".MBR";
        RecordFormat format = new AS400FileRecordDescription(system, path).retrieveRecordFormat()[0];
        SequentialFile seq = new SequentialFile(system, path);
        seq.setRecordFormat(format);
        seq.open(AS400File.READ_ONLY, 0, AS400File.COMMIT_LOCK_LEVEL_NONE);
        List<String> lines = new ArrayList<>();
        try {
            Record record;
            while ((record = seq.readNext()) != null) {
                lines.add(String.valueOf(record.getField("SRCDTA")).stripTrailing());
            }
        } finally {
            seq.close();
        }
        return lines;
    }

    /** The source physical files of a library (FILE_TYPE S). */
    public static List<String> sourceFiles(String lib) throws SQLException {
        List<String> files = new ArrayList<>();
        try (Connection c = As400Connection.openConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM QSYS2.SYSTABLES WHERE TABLE_SCHEMA = ? AND FILE_TYPE = 'S' ORDER BY TABLE_NAME")) {
                ps.setString(1, lib);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        files.add(rs.getString(1).trim());
                    }
                }
            }
        }
        return files;
    }

    public static String validName(String value, String field) {
        String name = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(field + " fehlt oder ist kein gültiger IBM-i-Name");
        }
        return name;
    }

    private static String cut(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "...";
    }
}
