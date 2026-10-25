package com.simweaver.rift.map.shape;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The attribute table of a shapefile (dBASE III/IV {@code .dbf}): only the fields asked for are
 * kept, as trimmed text, one row per shape record.
 *
 * <p>Text encoding: the {@code .cpg} file next to it when there is one ("UTF-8", "1252",
 * "ISO-8859-1", ...), else the language driver octet of the header (0x57 / 0x03: Windows-1252,
 * 0x01: CP437, 0x02: CP850, ...), else each value is read as UTF-8 and, when that fails, as
 * Windows-1252 (the usual case for Swedish data without a .cpg).
 */
public final class Dbf {

    /** The field names of the file, in order. */
    public final List<String> fields;
    /** The encoding used ("UTF-8", or "UTF-8, else windows-1252"). */
    public final String encoding;
    private final Map<String, String[]> values;
    private final int rows;

    private Dbf(List<String> fields, String encoding, Map<String, String[]> values, int rows) {
        this.fields = Collections.unmodifiableList(fields);
        this.encoding = encoding;
        this.values = values;
        this.rows = rows;
    }

    /** @return number of rows */
    public int rows() {
        return rows;
    }

    /** @return true when the field was asked for and exists (case is ignored) */
    public boolean has(String field) {
        return field != null && values.containsKey(field.toUpperCase(Locale.ROOT));
    }

    /** @return the value of a wanted field in a row, "" when empty, null when there is no such field or row */
    public String get(String field, int row) {
        String[] v = field == null ? null : values.get(field.toUpperCase(Locale.ROOT));
        return v == null || row < 0 || row >= v.length ? null : v[row];
    }

    /**
     * Reads a .dbf.
     *
     * @param dbf    the file
     * @param wanted field names to keep (case is ignored); others are skipped
     */
    public static Dbf read(File dbf, Collection<String> wanted) throws IOException {
        File cpg = sibling(dbf, ".cpg");
        String cpgText = cpg == null ? null : new String(Files.readAllBytes(cpg.toPath()), StandardCharsets.ISO_8859_1);
        try (InputStream raw = new FileInputStream(dbf)) {
            return read(raw, cpgText, wanted, dbf.getName());
        }
    }

    /**
     * Reads a .dbf from a stream (a file, or an entry of a zip package).
     *
     * @param stream  the .dbf content; not closed
     * @param cpgText the content of the .cpg file, or null when there is none
     * @param wanted  field names to keep (case is ignored); others are skipped
     * @param name    the file name, for messages
     */
    public static Dbf read(InputStream stream, String cpgText, Collection<String> wanted, String name) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 1 << 16));
        byte[] head = new byte[32];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int count = h.getInt(4);
        int headerLength = h.getShort(8) & 0xFFFF;
        int recordLength = h.getShort(10) & 0xFFFF;
        int ldid = head[29] & 0xFF;
        if (count < 0 || headerLength < 33 || recordLength < 1) {
            throw new IOException(name + ": not a dBASE file");
        }
        List<String> names = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();            // {offset in record, length}
        int read = 32;
        int offset = 1;                                   // after the deletion flag
        while (read + 32 <= headerLength) {
            int first = in.readUnsignedByte();
            read++;
            if (first == 0x0D) {
                break;
            }
            byte[] d = new byte[32];
            d[0] = (byte) first;
            in.readFully(d, 1, 31);
            read += 31;
            int end = 0;
            while (end < 11 && d[end] != 0) {
                end++;
            }
            String field = new String(d, 0, end, StandardCharsets.ISO_8859_1).trim();
            int length = d[16] & 0xFF;
            if ((d[11] == 'C') && (d[17] & 0xFF) > 0) {
                length += (d[17] & 0xFF) << 8;              // long character fields (Clipper/FoxPro)
            }
            names.add(field);
            spans.add(new int[] {offset, length});
            offset += length;
        }
        skip(in, headerLength - read);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (String w : wanted) {
            if (w == null) {
                continue;
            }
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(w.trim())) {
                    index.put(w.trim().toUpperCase(Locale.ROOT), i);
                }
            }
        }
        Charset cs = charset(cpgText, ldid);
        Map<String, String[]> values = new LinkedHashMap<>();
        for (String k : index.keySet()) {
            values.put(k, new String[count]);
        }
        byte[] rec = new byte[recordLength];
        int rows = 0;
        for (; rows < count; rows++) {
            try {
                in.readFully(rec);
            } catch (EOFException e) {
                break;                                     // a short file: the rows read so far
            }
            for (Map.Entry<String, Integer> e : index.entrySet()) {
                int[] s = spans.get(e.getValue());
                int len = Math.max(0, Math.min(s[1], recordLength - s[0]));
                values.get(e.getKey())[rows] = decode(rec, s[0], len, cs);
            }
        }
        return new Dbf(names, cs == null ? "UTF-8, else windows-1252" : cs.name(), values, rows);
    }

    private static void skip(DataInputStream in, int n) throws IOException {
        while (n > 0) {
            int s = (int) in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) {
                    return;
                }
                s = 1;
            }
            n -= s;
        }
    }

    /** The encoding of the .cpg file or of the language driver octet; null = UTF-8 with a windows-1252 fallback. */
    static Charset charset(String cpgText, int ldid) {
        if (cpgText != null && !cpgText.trim().isEmpty()) {
            Charset c = named(cpgText.trim().toUpperCase(Locale.ROOT));
            if (c != null) {
                return c;
            }
        }
        switch (ldid) {
            case 0x01: return named("IBM437");
            case 0x02: case 0x64: return named("IBM850");
            case 0x03: case 0x57: case 0x58: case 0x59: return named("WINDOWS-1252");
            case 0x26: case 0x65: return named("IBM866");
            case 0xC8: return named("WINDOWS-1250");
            case 0xC9: return named("WINDOWS-1251");
            default: return null;
        }
    }

    private static Charset named(String t) {
        String s = t.replace("ANSI", "").replace("CP", "").trim();
        if (s.equals("UTF-8") || s.equals("UTF8") || s.equals("65001")) {
            return StandardCharsets.UTF_8;
        }
        if (s.matches("\\d{3,4}")) {
            s = (s.startsWith("12") ? "windows-" : "IBM") + s;
        }
        if (s.equals("88591") || s.equals("8859_1") || s.equals("ISO 8859-1") || s.equals("LATIN1")) {
            return StandardCharsets.ISO_8859_1;
        }
        try {
            return Charset.forName(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String decode(byte[] b, int off, int len, Charset cs) {
        int end = off + len;
        while (end > off && (b[end - 1] == ' ' || b[end - 1] == 0)) {
            end--;
        }
        int start = off;
        while (start < end && b[start] == ' ') {
            start++;
        }
        if (start == end) {
            return "";
        }
        if (cs != null) {
            return new String(b, start, end - start, cs);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, start, end - start)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, start, end - start, Charset.forName("windows-1252"));
        }
    }

    /** The file with the same base name and another extension (either case), or null. */
    static File sibling(File f, String ext) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String base = dot < 0 ? n : n.substring(0, dot);
        for (String e : new String[] {ext, ext.toUpperCase(Locale.ROOT)}) {
            File s = new File(f.getParentFile(), base + e);
            if (s.isFile()) {
                return s;
            }
        }
        return null;
    }
}
