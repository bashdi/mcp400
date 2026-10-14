package com.github.bashdi.mcp400.pgm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A local source file that was checked and read once. The lines are the exact content that is transferred,
 * the sha256 proves that the file was not changed in between.
 * sourceType is null for copy members, which have no compile type.
 */
public record LocalSource(Path path, String sourceType, String member, List<String> lines, String sha256, List<String> warnings) {

    static final int MAX_BYTES = 512 * 1024;
    static final int MAX_LINES = 9999;
    static final int MAX_LINE_LENGTH = 100;

    /** Supported source types. */
    static final Set<String> SOURCE_TYPES = Set.of("RPGLE", "SQLRPGLE", "CLLE");
    private static final Map<String, String> EXTENSIONS = Map.of("rpgle", "RPGLE", "sqlrpgle", "SQLRPGLE", "clle", "CLLE");
    private static final Pattern MEMBER = Pattern.compile("[A-Z#@$][A-Z0-9#@$_]{0,9}");

    /** Checks the file, determines the type from the extension or explicitType, and reads it. */
    public static LocalSource load(String sourcePath, String explicitType) throws IOException {
        Path path = checkedFile(sourcePath);
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
        String baseName = dot < 0 ? fileName : fileName.substring(0, dot);

        List<String> warnings = new ArrayList<>();
        String type = resolveType(explicitType, extension, warnings);
        return read(path, type, memberName(baseName), warnings);
    }

    /** A copy member (/copy file,member): any extension, the member name is the file name. */
    public static LocalSource readMember(String sourcePath) throws IOException {
        Path path = checkedFile(sourcePath);
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot < 0 ? fileName : fileName.substring(0, dot);
        return read(path, null, memberName(baseName), new ArrayList<>());
    }

    private static Path checkedFile(String sourcePath) throws IOException {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("source_path fehlt");
        }
        Path path;
        try {
            path = Path.of(sourcePath.trim()).toRealPath();
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("Datei existiert nicht: " + sourcePath);
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("Datei ist keine lesbare Datei: " + sourcePath);
        }
        if (Files.size(path) > MAX_BYTES) {
            throw new IllegalArgumentException("Datei zu groß: max. " + MAX_BYTES / 1024 + " KB");
        }
        return path;
    }

    private static String memberName(String baseName) {
        String member = baseName.toUpperCase();
        if (!MEMBER.matcher(member).matches()) {
            throw new IllegalArgumentException("Dateiname '" + baseName + "' ist kein gültiger Membername (max. 10 Zeichen, A-Z, 0-9, # @ $ _, Anfangszeichen ohne Ziffer)");
        }
        return member;
    }

    private static LocalSource read(Path path, String type, String member, List<String> warnings) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String sha256 = HexFormat.of().formatHex(sha256(bytes));
        String text = decodeUtf8(bytes);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<String> lines = splitLines(text);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Datei ist leer: " + path.getFileName());
        }
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("Zu viele Zeilen in " + path.getFileName() + ": " + lines.size() + ", max. " + MAX_LINES);
        }
        int nonAscii = 0;
        int firstNonAscii = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                throw new IllegalArgumentException(path.getFileName() + ": Zeile " + (i + 1) + " ist länger als " + MAX_LINE_LENGTH + " Zeichen");
            }
            if (!line.chars().allMatch(c -> c < 128)) {
                if (nonAscii++ == 0) firstNonAscii = i + 1;
            }
        }
        if (nonAscii > 0) {
            warnings.add(path.getFileName() + ": Nicht-ASCII-Zeichen in " + nonAscii + " Zeile(n), erste in Zeile " + firstNonAscii + ": werden in die Job-CCSID konvertiert");
        }
        return new LocalSource(path, type, member, List.copyOf(lines), sha256, List.copyOf(warnings));
    }

    private static String resolveType(String explicitType, String extension, List<String> warnings) {
        String extensionType = EXTENSIONS.get(extension);
        if (explicitType != null && !explicitType.isBlank()) {
            String type = explicitType.trim().toUpperCase();
            if (!SOURCE_TYPES.contains(type)) {
                throw new IllegalArgumentException("Source-Typ nicht unterstützt: " + type + " (erlaubt: RPGLE, SQLRPGLE, CLLE)");
            }
            if (extensionType != null && !extensionType.equals(type)) {
                throw new IllegalArgumentException("Source-Typ " + type + " passt nicht zur Endung ." + extension);
            }
            if (extensionType == null) {
                warnings.add("Endung '." + extension + "' unbekannt, Typ " + type + " aus Parameter verwendet");
            }
            return type;
        }
        if (extensionType == null) {
            throw new IllegalArgumentException("Source-Typ nicht erkennbar: Endung '." + extension + "' unbekannt (erlaubt: .rpgle, .sqlrpgle, .clle), source_type angeben");
        }
        return extensionType;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Datei ist kein gültiges UTF-8");
        }
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
