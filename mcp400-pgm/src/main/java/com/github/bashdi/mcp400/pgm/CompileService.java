package com.github.bashdi.mcp400.pgm;

import com.github.bashdi.mcp400.core.As400Connection;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileOutputStream;
import com.ibm.as400.access.Job;
import com.ibm.as400.access.JobLog;
import com.ibm.as400.access.PrintObjectInputStream;
import com.ibm.as400.access.QSYSObjectPathName;
import com.ibm.as400.access.QueuedMessage;
import com.ibm.as400.access.SpooledFile;
import com.ibm.as400.access.SpooledFileList;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles a local source on the IBM i in a temporary source file in QTEMP.
 * prepare_compile copies the source (and optional copy members) into QTEMP, compile_check compiles it and removes what it created again.
 * Nothing is run. Every step uses a fixed command template; only validated values are inserted into it.
 * The connection is dedicated per compile: QTEMP and the joblog belong to that job.
 */
public final class CompileService {

    /** Temporary source file in QTEMP, the same name for every source type. */
    private static final String SOURCE_FILE = "TMPSRC";
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_OPEN = 5;
    private static final int MAX_COPY_MEMBERS = 50;
    private static final int MAX_LIBRARIES = 20;
    private static final int MAX_DIAGNOSTICS = 50;
    private static final int MAX_SPOOL_HINTS = 30;
    private static final int MAX_SPOOL_BYTES = 500_000;
    private static final int MAX_JOBLOG_LINES = 20;
    private static final int MAX_SECOND_LEVEL_CHARS = 400;
    private static final int ROWS_PER_INSERT = 100;
    /** Added to the library list for every compile: QDEVTOOLS holds the compiler message file QRPGLEMSG. */
    private static final List<String> COMPILER_LIBRARIES = List.of("QDEVTOOLS");
    /** IBM i message ID, e.g. CPF5813 */
    private static final Pattern MESSAGE_ID = Pattern.compile("\\b([A-Z]{3}\\d{4})\\b");
    private static final Set<String> ALLOWED_OPTIONS = Set.of("*XREF", "*NOXREF", "*SECLVL", "*NOSECLVL");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern IBM_NAME = Pattern.compile("[A-Z#@$][A-Z0-9#@$_]{0,9}");
    /** Unqualified copy reference, e.g. "/copy src,LZLOGH" */
    private static final Pattern COPY_REFERENCE = Pattern.compile("(?i)/copy\\s+([A-Z0-9#@$_]+),([A-Z0-9#@$_]+)");
    private static final Pattern LINE_NUMBER = Pattern.compile("(?i)\\b(?:line|zeile)\\s+(\\d+)");
    private static final Pattern SPOOL_HINT = Pattern.compile("(?i)\\*{3}|\\berror\\b|\\bwarning\\b|\\bsevere\\b");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Prepared> prepared = new HashMap<>();

    /** @param copyFiles source files in QTEMP that hold copy members, to delete again */
    private record Prepared(Connection connection, LocalSource source, String options, String jobName, List<String> copyFiles, Instant created) {
    }

    /** The copy members of one source file, e.g. "/copy src,X" -> file SRC with member X. */
    private record CopyGroup(String file, List<LocalSource> members) {
    }

    private record CopySet(List<CopyGroup> groups, List<String> warnings) {
    }

    /** A "/copy FILE,MEMBER" line in the source. */
    private record Reference(String file, String member) {
    }

    private record Message(long ordinal, String id, int severity, String text, String secondLevel) {
        int line() {
            Matcher m = LINE_NUMBER.matcher(text + " " + (secondLevel == null ? "" : secondLevel));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }
    }

    // ------------------------------------------------------------------ prepare_compile

    public synchronized String prepare(String sourcePath, String sourceType, String compilerOptions, String copyMembers, String libraries) {
        closeExpired();
        if (prepared.size() >= MAX_OPEN) {
            throw new IllegalStateException("Zu viele offene Vorbereitungen (max. " + MAX_OPEN + "): erst compile_check ausführen");
        }
        Timings timings = new Timings();
        LocalSource source = timings.run("Datei prüfen", () -> LocalSource.load(sourcePath, sourceType));
        CopySet copies = timings.run("Copy-Member prüfen", () -> readCopySet(source, copyMembers));
        String options = timings.run("Optionen prüfen", () -> validateOptions(compilerOptions));
        List<String> libs = timings.run("Bibliotheken prüfen", () -> parseLibraries(libraries));
        Connection c = timings.run("Verbindung", As400Connection::openConnection);
        String job = jobName(c);
        long baseline = joblogBaselineOrZero(job);
        List<String> added = List.of();
        try {
            added = timings.run("Bibliotheksliste", () -> addLibraries(c, libs));
            timings.run("Source anlegen", () -> {
                createSources(c, source, copies);
                return null;
            });
            timings.run("Übertragung", () -> {
                upload(c, SOURCE_FILE, source.lines());
                if (!copies.groups().isEmpty()) {
                    AS400 system = As400Connection.system();
                    try {
                        for (CopyGroup group : copies.groups()) {
                            for (LocalSource member : group.members()) {
                                uploadCopyMember(c, system, group.file(), member);
                            }
                        }
                    } finally {
                        system.disconnectAllServices();
                    }
                }
                return null;
            });
            timings.run("Gegenprüfung", () -> {
                int count = countRows(c, SOURCE_FILE);
                if (count != source.lines().size()) {
                    throw new IllegalStateException("erwartet " + source.lines().size() + " Zeilen, im Member sind " + count);
                }
                return null;
            });
        } catch (StepError e) {
            String joblog = joblogSince(job, baseline);
            close(c);
            throw new IllegalStateException(e.getMessage() + "\n" + joblog);
        } catch (RuntimeException e) {
            close(c);
            throw e;
        }

        String id = newId();
        prepared.put(id, new Prepared(c, source, options, job, copies.groups().stream().map(CopyGroup::file).toList(), Instant.now()));

        List<String> out = new ArrayList<>();
        out.add("compile_id: " + id);
        out.add("ready: yes");
        out.add("source: " + source.path());
        out.add("source_type: " + source.sourceType());
        out.add("temporary: QTEMP/" + SOURCE_FILE + "(" + source.member() + ")");
        out.add("libraries: QTEMP, " + String.join(", ", added));
        for (CopyGroup group : copies.groups()) {
            out.add("copy: QTEMP/" + group.file() + " " + group.members().stream().map(LocalSource::member).toList());
        }
        out.add("lines: " + source.lines().size() + ", sha256: " + source.sha256().substring(0, 12));
        out.add("options: " + (options.isEmpty() ? "default" : options));
        out.add("timing: " + timings.summary());
        List<String> warnings = new ArrayList<>(source.warnings());
        warnings.addAll(copies.warnings());
        if (warnings.isEmpty()) {
            out.add("warnings: none");
        } else {
            warnings.forEach(w -> out.add("warning: " + w));
        }
        return String.join("\n", out);
    }

    // ------------------------------------------------------------------ compile_check

    public synchronized String check(String compileId) {
        if (compileId == null || !ID.matcher(compileId).matches()) {
            throw new IllegalArgumentException("compile_id ungültig");
        }
        Prepared p = prepared.remove(compileId);
        if (p == null) {
            throw new IllegalArgumentException("compile_id unbekannt oder abgelaufen: erst prepare_compile ausführen");
        }
        closeExpired();
        Report report = new Report(compileId, p.source());
        Connection c = p.connection();
        try {
            compile(c, p, report);
        } finally {
            report.cleanup = cleanup(c, p, report);
            close(c);
        }
        return report.render();
    }

    /** Runs the compile, reads the joblog and the listing, and decides the status. */
    private void compile(Connection c, Prepared p, Report r) {
        LocalSource src = p.source();
        try {
            if (countRows(c, SOURCE_FILE) != src.lines().size()) {
                r.infrastructure = "temporary source is missing or incomplete";
                return;
            }
        } catch (SQLException e) {
            r.infrastructure = "connection to the IBM i is gone (" + clean(e.getMessage()) + ")";
            return;
        }

        long baseline;
        try {
            baseline = joblogBaseline(p.jobName());
        } catch (IllegalStateException e) {
            r.infrastructure = "joblog cannot be read (" + clean(e.getMessage()) + ")";
            return;
        }

        try {
            runCommand(c, compileCommand(src, p.options()));
        } catch (SQLException e) {
            if (As400Connection.isConnectionLost(e)) {
                r.infrastructure = "connection lost during compile (" + clean(e.getMessage()) + ")";
                return;
            }
            r.commandError = clean(e.getMessage());
        }

        try {
            r.messages = readJoblog(p.jobName(), baseline);
            long after = joblogBaseline(p.jobName());
            r.joblogInfo = "job " + p.jobName() + ", messages before compile " + baseline + ", after compile " + after;
        } catch (IllegalStateException e) {
            r.infrastructure = "joblog cannot be read (" + clean(e.getMessage()) + ")";
            return;
        }

        try {
            r.objects = objectsNamed(c, src.member());
        } catch (SQLException e) {
            r.objectsError = clean(e.getMessage());
        }

        try {
            r.spool = readSpool(p.jobName());
        } catch (IllegalStateException e) {
            r.spoolError = clean(e.getMessage());
        }
    }

    private String cleanup(Connection c, Prepared p, Report r) {
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            if (!r.objects.isEmpty()) {
                runCommand(c, "DLTOBJ OBJ(QTEMP/" + p.source().member() + ") OBJTYPE(*ALL)");
                done.add("QTEMP/" + p.source().member());
            }
            runCommand(c, "DLTF FILE(QTEMP/" + SOURCE_FILE + ")");
            done.add("QTEMP/" + SOURCE_FILE);
            for (String copyFile : p.copyFiles()) {
                runCommand(c, "DLTF FILE(QTEMP/" + copyFile + ")");
                done.add("QTEMP/" + copyFile);
            }
        } catch (SQLException e) {
            failed.add(clean(e.getMessage()));
        }
        if (r.spool != null) {
            try {
                runCommand(c, "DLTSPLF FILE(QSYSPRT) JOB(" + r.spool.job() + ") SPLNBR(" + r.spool.number() + ")");
                done.add("spool " + r.spool.number());
            } catch (SQLException e) {
                failed.add("spool: " + clean(e.getMessage()));
            }
        }
        if (failed.isEmpty()) {
            return "completed (" + String.join(", ", done) + " removed)";
        }
        return "incomplete: " + String.join("; ", failed) + (done.isEmpty() ? "" : " (removed: " + String.join(", ", done) + ")");
    }

    // ------------------------------------------------------------------ copy members

    /**
     * Finds the copy members the source needs. Each "/copy FILE,MEMBER" line names the source file in QTEMP
     * (SRC for "/copy src,X", QRPGLESRC for "/copy qrpglesrc,X"). The member comes from copy_members if given there,
     * otherwise from the folder of the source. Qualified copies ("/copy lib/file,X") are not matched and stay with the library list.
     */
    static CopySet readCopySet(LocalSource source, String copyMembers) throws IOException {
        List<String> paths = new ArrayList<>();
        if (copyMembers != null) {
            for (String path : copyMembers.split(";")) {
                if (!path.isBlank()) paths.add(path.trim());
            }
        }
        if (paths.size() > MAX_COPY_MEMBERS) {
            throw new IllegalArgumentException("Zu viele Copy-Member: " + paths.size() + ", max. " + MAX_COPY_MEMBERS);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, LocalSource> explicit = new LinkedHashMap<>();
        for (String path : paths) {
            LocalSource member = LocalSource.readMember(path);
            if (explicit.putIfAbsent(member.member(), member) != null) {
                throw new IllegalArgumentException("Copy-Member " + member.member() + " ist mehrfach angegeben");
            }
            warnings.addAll(member.warnings());
        }

        // target file -> members, in the order of the /copy lines
        Map<String, Set<String>> needed = new LinkedHashMap<>();
        for (Reference r : references(source.lines())) {
            if (!IBM_NAME.matcher(r.file()).matches()) {
                throw new IllegalArgumentException("/copy " + r.file() + "," + r.member() + ": Dateiname ist kein gültiger IBM-i-Name");
            }
            if (r.file().equals(SOURCE_FILE)) {
                throw new IllegalArgumentException("/copy " + r.file() + "," + r.member() + ": " + SOURCE_FILE + " ist für den Quelltext reserviert");
            }
            needed.computeIfAbsent(r.file(), k -> new LinkedHashSet<>()).add(r.member());
        }
        if (needed.isEmpty() && explicit.isEmpty()) {
            return new CopySet(List.of(), List.copyOf(warnings));
        }

        Path folder = source.path().getParent();
        List<CopyGroup> groups = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : needed.entrySet()) {
            List<LocalSource> members = new ArrayList<>();
            for (String name : entry.getValue()) {
                LocalSource member = explicit.get(name);
                if (member == null) {
                    Path found = findCopyMember(folder, name);
                    if (found == null) {
                        throw new IllegalArgumentException("/copy " + entry.getKey() + "," + name + ": Copy-Member nicht gefunden (weder in copy_members noch im Ordner " + folder + ")");
                    }
                    member = LocalSource.readMember(found.toString());
                    warnings.add("Copy-Member " + name + " aus " + found.getFileName() + " im Quellordner übernommen");
                    warnings.addAll(member.warnings());
                }
                members.add(member);
            }
            groups.add(new CopyGroup(entry.getKey(), List.copyOf(members)));
        }

        Set<String> referenced = new HashSet<>();
        needed.values().forEach(referenced::addAll);
        for (String name : explicit.keySet()) {
            if (!referenced.contains(name)) {
                warnings.add("Copy-Member " + name + " wird im Quelltext nicht per /copy referenziert und nicht übertragen");
            }
        }
        return new CopySet(List.copyOf(groups), List.copyOf(warnings));
    }

    /** The one file in the folder whose name without extension is the member name; null if there is none. */
    private static Path findCopyMember(Path folder, String name) throws IOException {
        List<Path> hits;
        try (Stream<Path> files = Files.list(folder)) {
            hits = files.filter(Files::isRegularFile)
                    .filter(p -> baseName(p).equalsIgnoreCase(name))
                    .sorted()
                    .toList();
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException("Copy-Member " + name + " ist im Quellordner mehrdeutig: " + hits.stream().map(p -> p.getFileName().toString()).toList());
        }
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static List<Reference> references(List<String> lines) {
        Set<Reference> found = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher m = COPY_REFERENCE.matcher(line);
            while (m.find()) {
                found.add(new Reference(m.group(1).toUpperCase(), m.group(2).toUpperCase()));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Copy member: written as a stream file under /tmp and copied into the member of QTEMP/<file> with CPYFRMSTMF.
     * CPYFRMSTMF runs in the JDBC job, so the member lands in the same QTEMP as the compile.
     * SQL inserts cannot address a member other than the default one, and a member override did not apply to them.
     */
    private static void uploadCopyMember(Connection c, AS400 system, String file, LocalSource member) throws Exception {
        String stmf = "/tmp/mcp400/" + newId() + ".txt";
        try {
            writeStream(system, stmf, member.lines());
            runCommand(c, "CPYFRMSTMF FROMSTMF('" + stmf + "') TOMBR('/QSYS.LIB/QTEMP.LIB/" + file + ".FILE/" + member.member() + ".MBR')"
                    + " MBROPT(*REPLACE) STMFCODPAG(1208) ENDLINFMT(*LF)");
        } finally {
            deleteStream(system, stmf);
        }
    }

    private static void writeStream(AS400 system, String stmf, List<String> lines) throws Exception {
        IFSFile dir = new IFSFile(system, "/tmp/mcp400");
        if (!dir.exists()) {
            dir.mkdir();
        }
        try (OutputStream out = new IFSFileOutputStream(system, stmf)) {
            out.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteStream(AS400 system, String stmf) {
        try {
            new IFSFile(system, stmf).delete();
        } catch (Exception ignored) {
            // the stream file stays in /tmp/mcp400 if the IBM i cannot delete it now
        }
    }

    // ------------------------------------------------------------------ IBM i steps

    /**
     * Adds the compiler libraries, then the libraries the caller named, to the library list (at the end; QTEMP stays first).
     * The tables of the source are found through the caller's libraries.
     */
    private static List<String> addLibraries(Connection c, List<String> requested) throws SQLException {
        List<String> added = new ArrayList<>();
        for (String lib : COMPILER_LIBRARIES) {
            added.add(addLibrary(c, lib));
        }
        for (String lib : requested) {
            added.add(addLibrary(c, lib));
        }
        return added;
    }

    private static String addLibrary(Connection c, String lib) throws SQLException {
        try {
            runCommand(c, "ADDLIBLE LIB(" + lib + ") POSITION(*LAST)");
        } catch (SQLException e) {
            // already in the library list
            if (e.getMessage() == null || !e.getMessage().contains("CPF2103")) {
                throw e;
            }
        }
        return lib;
    }

    /** Library names from the caller, separated by comma, semicolon or blank. */
    static List<String> parseLibraries(String raw) {
        List<String> libs = new ArrayList<>();
        if (raw == null || raw.isBlank()) return libs;
        for (String token : raw.trim().toUpperCase().split("[,;\\s]+")) {
            if (token.isEmpty()) continue;
            if (!IBM_NAME.matcher(token).matches()) {
                throw new IllegalArgumentException("libraries: '" + token + "' ist kein gültiger IBM-i-Bibliotheksname");
            }
            if (!libs.contains(token)) libs.add(token);
        }
        if (libs.size() > MAX_LIBRARIES) {
            throw new IllegalArgumentException("Zu viele Bibliotheken: " + libs.size() + ", max. " + MAX_LIBRARIES);
        }
        return libs;
    }

    private static void createSources(Connection c, LocalSource source, CopySet copies) throws SQLException {
        runCommand(c, "CRTSRCPF FILE(QTEMP/" + SOURCE_FILE + ") RCDLEN(112) MBR(" + source.member() + ")");
        if (copies.groups().isEmpty()) {
            return;
        }
        for (CopyGroup group : copies.groups()) {
            List<LocalSource> members = group.members();
            runCommand(c, "CRTSRCPF FILE(QTEMP/" + group.file() + ") RCDLEN(112) MBR(" + members.get(0).member() + ")");
            for (LocalSource member : members.subList(1, members.size())) {
                runCommand(c, "ADDPFM FILE(QTEMP/" + group.file() + ") MBR(" + member.member() + ")");
            }
        }
        // QTEMP must be the first library, otherwise /copy finds another SRC; if it is in the list already, ADDLIBLE keeps its position
        try {
            runCommand(c, "RMVLIBLE LIB(QTEMP)");
        } catch (SQLException e) {
            // QTEMP is not in the library list yet
        }
        runCommand(c, "ADDLIBLE LIB(QTEMP) POSITION(*FIRST)");
    }

    /**
     * Writes the lines as multi-row INSERTs of ROWS_PER_INSERT rows each (one round trip per chunk).
     * No transaction: commitment control does not work on the non-journaled files in QTEMP.
     */
    private static void upload(Connection c, String file, List<String> lines) throws SQLException {
        for (int start = 0; start < lines.size(); start += ROWS_PER_INSERT) {
            int end = Math.min(start + ROWS_PER_INSERT, lines.size());
            StringBuilder sql = new StringBuilder("INSERT INTO QTEMP." + file + " (SRCSEQ, SRCDAT, SRCDTA) VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(", ");
                sql.append("(?, 0, ?)");
            }
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = start; i < end; i++) {
                    ps.setBigDecimal((i - start) * 2 + 1, BigDecimal.valueOf(i + 1));
                    ps.setString((i - start) * 2 + 2, lines.get(i));
                }
                try {
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new SQLException("Zeilen " + (start + 1) + " bis " + end + ": " + clean(e.getMessage()), e.getSQLState(), e.getErrorCode(), e);
                }
            }
        }
    }

    private static int countRows(Connection c, String file) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM QTEMP." + file)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Fixed command templates; the options are validated against ALLOWED_OPTIONS. */
    static String compileCommand(LocalSource src, String options) {
        String m = src.member();
        // OUTPUT(*PRINT) writes the compiler listing (QSYSPRT) to the job's output queue; the line-level diagnostics are only in there
        String opt = (options.isEmpty() ? "" : " OPTION(" + options + ")") + " OUTPUT(*PRINT)";
        return switch (src.sourceType()) {
            case "RPGLE" -> "CRTRPGMOD MODULE(QTEMP/" + m + ") SRCFILE(QTEMP/" + SOURCE_FILE + ") SRCMBR(" + m + ")" + opt;
            case "SQLRPGLE" -> "CRTSQLRPGI OBJ(QTEMP/" + m + ") SRCFILE(QTEMP/" + SOURCE_FILE + ") SRCMBR(" + m + ") COMMIT(*NONE)" + opt;
            case "CLLE" -> "CRTCLMOD MODULE(QTEMP/" + m + ") SRCFILE(QTEMP/" + SOURCE_FILE + ") SRCMBR(" + m + ")" + opt;
            default -> throw new IllegalStateException("Source-Typ ohne Compile-Strategie: " + src.sourceType());
        };
    }

    /** Runs a CL command; a failure names the command, so the cause is visible in the error. */
    static void runCommand(Connection c, String command) throws SQLException {
        try (CallableStatement cs = c.prepareCall("CALL QSYS2.QCMDEXC(?, ?)")) {
            cs.setString(1, command);
            cs.setBigDecimal(2, BigDecimal.valueOf(command.length()));
            cs.execute();
        } catch (SQLException e) {
            throw new SQLException(command + ": " + clean(e.getMessage()), e.getSQLState(), e.getErrorCode(), e);
        }
    }

    private static String jobName(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT QSYS2.JOB_NAME FROM SYSIBM.SYSDUMMY1")) {
            return rs.next() ? rs.getString(1) : "*";
        } catch (SQLException e) {
            return "*";
        }
    }

    /**
     * The JTOpen job for a qualified name "number/user/job" as JDBC reports it. The one-argument Job constructor
     * takes an internal job ID instead, so the parts are passed separately.
     */
    private static Job jtopenJob(AS400 system, String qualifiedName) {
        String[] parts = qualifiedName.split("/");
        if (parts.length != 3) throw new IllegalStateException("Jobname nicht qualifiziert: " + qualifiedName);
        return new Job(system, parts[2], parts[1], parts[0]);
    }

    /** The joblog of the job, oldest message first, read through JTOpen. */
    private static QueuedMessage[] joblogMessages(String job) {
        AS400 system = As400Connection.system();
        try {
            JobLog log = jtopenJob(system, job).getJobLog();
            log.load();
            QueuedMessage[] all = log.getMessages(-1, 0);
            return all == null ? new QueuedMessage[0] : all;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("joblog nicht lesbar: unterbrochen", e);
        } catch (Exception e) {
            throw new IllegalStateException("joblog nicht lesbar: " + clean(e.getMessage()), e);
        } finally {
            system.disconnectAllServices();
        }
    }

    /** The number of joblog messages before the compile starts. */
    private static long joblogBaseline(String job) {
        return joblogMessages(job).length;
    }

    private static long joblogBaselineOrZero(String job) {
        try {
            return joblogBaseline(job);
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** The last joblog messages since the baseline, for a failed step: the IBM i message behind a generic CPF0006. */
    private static String joblogSince(String job, long baseline) {
        try {
            List<Message> messages = readJoblog(job, baseline);
            if (messages.isEmpty()) return "joblog: keine Meldungen";
            List<String> lines = new ArrayList<>(List.of("joblog (letzte Meldungen):"));
            for (Message m : messages.subList(Math.max(0, messages.size() - 8), messages.size())) {
                lines.add("  " + (m.id() == null ? "-" : m.id()) + " " + m.text());
                if (m.severity() >= 40 && m.secondLevel() != null && !m.secondLevel().isBlank()) {
                    lines.add("     " + m.secondLevel().strip().replaceAll("\\s+", " ").substring(0, Math.min(300, m.secondLevel().strip().length())));
                }
            }
            return String.join("\n", lines);
        } catch (IllegalStateException e) {
            return "joblog: nicht lesbar (" + clean(e.getMessage()) + ")";
        }
    }

    /** The messages after the baseline. The ordinal is the 1-based position in the joblog, as the IBM i numbers it. */
    private static List<Message> readJoblog(String job, long baseline) {
        QueuedMessage[] all = joblogMessages(job);
        List<Message> messages = new ArrayList<>();
        for (int i = (int) baseline; i < all.length; i++) {
            QueuedMessage q = all[i];
            messages.add(new Message(i + 1, q.getID(), q.getSeverity(), clean(q.getText()), clean(q.getHelp())));
        }
        return messages;
    }

    private static List<String> objectsNamed(Connection c, String member) throws SQLException {
        List<String> objects = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT OBJTYPE FROM TABLE(QSYS2.OBJECT_STATISTICS('QTEMP', '*ALL')) X WHERE OBJNAME = ?")) {
            ps.setString(1, member);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) objects.add(member + " " + rs.getString(1));
            }
        }
        return objects;
    }

    private record Spool(String job, int number, List<String> hints, int lines) {
    }

    /**
     * The newest QSYSPRT listing of this job, read through JTOpen. The job's output queue comes from the job itself,
     * and the spool files are matched to the job by name, number and user.
     */
    private static Spool readSpool(String job) {
        AS400 system = As400Connection.system();
        try {
            Job j = jtopenJob(system, job);
            String queue = queueFilter(j.getOutputQueue());
            SpooledFileList list = new SpooledFileList(system);
            try {
                list.setQueueFilter(queue);
                list.openSynchronously();
                SpooledFile newest = null;
                List<String> seen = new ArrayList<>();
                for (SpooledFile sf : list.getObjectsList()) {
                    if (seen.size() < 10) seen.add(sf.getName() + " von " + sf.getJobNumber() + "/" + sf.getJobUser() + "/" + sf.getJobName());
                    if (!"QSYSPRT".equals(sf.getName()) || !j.getName().equals(sf.getJobName()) || !j.getNumber().equals(sf.getJobNumber())) continue;
                    if (newest == null || sf.getNumber() > newest.getNumber()) newest = sf;
                }
                if (newest == null) {
                    throw new IllegalStateException("keine Spool-Datei QSYSPRT im Job " + job + " (Queue " + queue + ", "
                            + list.getObjectsList().size() + " Spool-Dateien dort" + (seen.isEmpty() ? "" : ": " + String.join("; ", seen)) + ")");
                }
                return readListing(job, newest);
            } finally {
                list.close();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Spool nicht lesbar: unterbrochen", e);
        } catch (Exception e) {
            throw new IllegalStateException("Spool nicht lesbar: " + clean(e.getMessage()), e);
        } finally {
            system.disconnectAllServices();
        }
    }

    /**
     * The IFS path of the output queue, which SpooledFileList.setQueueFilter expects (/QSYS.LIB/LIB.LIB/NAME.OUTQ).
     * JTOpen returns that path, or "LIB/NAME" for a queue given that way.
     */
    private static String queueFilter(String outputQueue) {
        if (outputQueue != null && outputQueue.startsWith("/")) return outputQueue;
        String[] parts = outputQueue == null ? new String[0] : outputQueue.split("/");
        if (parts.length != 2) throw new IllegalStateException("Ausgabewarteschlange unbekannt: " + outputQueue);
        return QSYSObjectPathName.toPath(parts[0], parts[1], "OUTQ");
    }

    /** The diagnostic lines of one listing. Only lines that look like diagnostics are kept. */
    private static Spool readListing(String job, SpooledFile listing) throws Exception {
        byte[] data;
        try (PrintObjectInputStream in = listing.getInputStream()) {
            data = in.readNBytes(MAX_SPOOL_BYTES);
        }
        String jobName = job;
        int number = listing.getNumber();
        String content = new String(data, StandardCharsets.ISO_8859_1);
        List<String> hints = new ArrayList<>();
        int lines = 0;
        for (String line : content.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            lines++;
            if (hints.size() < MAX_SPOOL_HINTS && SPOOL_HINT.matcher(line).find()) {
                hints.add(line.strip());
            }
        }
        return new Spool(jobName, number, hints, lines);
    }

    // ------------------------------------------------------------------ errors and timing

    private interface Step<T> {
        T run() throws Exception;
    }

    /** A failed step with its name, the cause in plain words and the timings so far. */
    static final class StepError extends RuntimeException {
        StepError(String step, String detail, String timings) {
            super("Schritt '" + step + "' fehlgeschlagen: " + detail + "\ntiming: " + timings);
        }
    }

    /** Runs the steps of prepare_compile, measures each one and turns a failure into a StepError. */
    private static final class Timings {
        private final List<String> steps = new ArrayList<>();

        <T> T run(String name, Step<T> step) {
            long start = System.nanoTime();
            try {
                T result = step.run();
                steps.add(name + " " + millis(start) + " ms");
                return result;
            } catch (Exception e) {
                steps.add(name + " " + millis(start) + " ms (fehlgeschlagen)");
                throw new StepError(name, describe(name, e), summary());
            }
        }

        String summary() {
            return String.join(", ", steps);
        }

        private static long millis(long start) {
            return (System.nanoTime() - start) / 1_000_000;
        }
    }

    /** The cause in plain words: IBM i message ID if there is one, connection loss as its own message. */
    private static String describe(String step, Exception e) {
        String detail;
        if (e instanceof SQLException sql) {
            if (As400Connection.isConnectionLost(sql)) {
                detail = "Verbindung zum IBM i verloren (" + clean(sql.getMessage()) + ")";
            } else {
                Matcher m = MESSAGE_ID.matcher(String.valueOf(sql.getMessage()));
                detail = (m.find() ? m.group(1) + ": " : "") + clean(sql.getMessage());
            }
        } else if (e.getMessage() == null) {
            detail = e.getClass().getSimpleName();
        } else {
            detail = clean(e.getMessage());
        }
        if (step.equals("Verbindung")) {
            detail += " (Adresse, Benutzer und Passwort prüfen)";
        }
        return detail;
    }

    // ------------------------------------------------------------------ helpers

    static String validateOptions(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> out = new ArrayList<>();
        for (String token : raw.trim().toUpperCase().split("[,\\s]+")) {
            if (!ALLOWED_OPTIONS.contains(token)) {
                throw new IllegalArgumentException("compiler_options: '" + token + "' nicht erlaubt (erlaubt: " + String.join(" ", ALLOWED_OPTIONS.stream().sorted().toList()) + ")");
            }
            out.add(token);
        }
        return String.join(" ", out);
    }

    private static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void closeExpired() {
        Instant limit = Instant.now().minus(TTL);
        for (Iterator<Prepared> it = prepared.values().iterator(); it.hasNext(); ) {
            Prepared p = it.next();
            if (p.created().isBefore(limit)) {
                close(p.connection());
                it.remove();
            }
        }
    }

    private static void close(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // already gone
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Collects everything the compile check found; rendered as lines of text. */
    private static final class Report {
        final String compileId;
        final LocalSource source;
        String infrastructure;
        String commandError;
        List<Message> messages = List.of();
        String joblogInfo;
        List<String> objects = List.of();
        String objectsError;
        Spool spool;
        String spoolError;
        String cleanup = "not run";

        Report(String compileId, LocalSource source) {
            this.compileId = compileId;
            this.source = source;
        }

        String render() {
            List<Message> errors = messages.stream().filter(m -> m.severity() >= 20).toList();
            List<Message> warnings = messages.stream().filter(m -> m.severity() >= 10 && m.severity() < 20).toList();
            int informational = messages.size() - errors.size() - warnings.size();

            String status;
            if (infrastructure != null) status = "infrastructure_error";
            else if (!errors.isEmpty() || commandError != null) status = "failed";
            else if (!warnings.isEmpty()) status = "success_with_warnings";
            else status = "success";

            List<String> out = new ArrayList<>();
            out.add("compile_id: " + compileId);
            out.add("status: " + status);
            out.add("source: " + source.path());
            out.add("source_type: " + source.sourceType());
            if (infrastructure != null) {
                out.add("reason: " + infrastructure);
            }
            if (commandError != null) {
                out.add("command: " + commandError);
            }
            out.add("summary: " + errors.size() + " errors, " + warnings.size() + " warnings, " + informational + " informational");
            if (objectsError != null) {
                out.add("object_created: unknown (" + objectsError + ")");
            } else {
                out.add("object_created: " + (objects.isEmpty() ? "no" : "yes (" + String.join(", ", objects) + ")"));
            }

            int shown = 0;
            for (Message m : errors) {
                if (shown++ >= MAX_DIAGNOSTICS) break;
                out.add(diagnostic("E", m));
            }
            for (Message m : warnings) {
                if (shown++ >= MAX_DIAGNOSTICS) break;
                out.add(diagnostic("W", m));
            }
            if (errors.size() + warnings.size() > MAX_DIAGNOSTICS) {
                out.add("... more diagnostics omitted");
            }

            out.add("joblog: " + (infrastructure != null && messages.isEmpty() ? "unavailable" : messages.size() + " messages since compile start")
                    + (joblogInfo == null ? "" : " (" + joblogInfo + ")"));
            for (Message m : messages.subList(0, Math.min(messages.size(), MAX_JOBLOG_LINES))) {
                out.add("  " + (m.id() == null ? "-" : m.id()) + " severity " + m.severity() + ": " + m.text());
                if (m.secondLevel() != null && !m.secondLevel().isBlank()) {
                    String second = m.secondLevel().strip().replaceAll("\\s+", " ");
                    out.add("     " + second.substring(0, Math.min(MAX_SECOND_LEVEL_CHARS, second.length())));
                }
            }
            if (spool != null) {
                out.add("spool: QSYSPRT " + spool.number() + ", " + spool.lines() + " lines");
                spool.hints().forEach(h -> out.add("  > " + h));
            } else {
                out.add("spool: unavailable (" + (spoolError == null ? "not read" : spoolError) + ")");
            }
            out.add("cleanup: " + cleanup);
            return String.join("\n", out);
        }

        private static String diagnostic(String kind, Message m) {
            int line = m.line();
            return kind + " " + (line < 0 ? "-" : String.valueOf(line)) + " " + (m.id() == null ? "-" : m.id()) + " " + m.text();
        }
    }

    /** The status tokens of compile_check, for the tool description. */
    static final String STATUSES = "success, success_with_warnings, failed, infrastructure_error";
}
