package com.simweaver.rift.map.shape;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Where the files of one shapefile layer are: next to each other in a folder, or inside a
 * {@code .zip} package (at any depth, with the same base name), as Natural Earth and most data
 * portals hand them out. Nothing is unpacked to disk.
 */
public abstract class ShapeSource {

    /** The .shp file name without its extension: the layer's id. */
    public final String baseName;

    ShapeSource(String baseName) {
        this.baseName = baseName;
    }

    /** @return the layer's files for the log: "D:/gis/towns.shp" or "D:/gis/towns.zip!data/towns.shp" */
    public abstract String name();

    /** @return the file name shown to users: "towns.shp" or "towns.zip!towns.shp" */
    public abstract String shortName();

    /**
     * @param ext ".shp", ".dbf", ".prj" or ".cpg" (either case is found)
     * @return the file's content stream, or null when the layer has no such file
     */
    public abstract InputStream open(String ext) throws IOException;

    /** @return changes when any of the layer's files change (sizes and times) */
    public abstract long stamp();

    /** @return the same layer: same files */
    public abstract boolean sameAs(ShapeSource other);

    // ------------------------------------------------------------------ files in a folder

    /** The layer of a .shp file in a folder. */
    public static ShapeSource file(File shp) {
        return new FileSource(shp);
    }

    private static final class FileSource extends ShapeSource {
        private final File shp;

        FileSource(File shp) {
            super(base(shp.getName()));
            this.shp = shp;
        }

        @Override
        public String name() {
            return shp.getPath();
        }

        @Override
        public String shortName() {
            return shp.getName();
        }

        @Override
        public InputStream open(String ext) throws IOException {
            File f = ext.equalsIgnoreCase(".shp") ? shp : Dbf.sibling(shp, ext);
            return f == null || !f.isFile() ? null : new FileInputStream(f);
        }

        @Override
        public long stamp() {
            long h = 17;
            for (String ext : new String[] {".dbf", ".prj", ".cpg"}) {
                File f = Dbf.sibling(shp, ext);
                if (f != null) {
                    h = h * 31 + f.length();
                    h = h * 31 + f.lastModified();
                }
            }
            return (h * 31 + shp.length()) * 31 + shp.lastModified();
        }

        @Override
        public boolean sameAs(ShapeSource other) {
            return other instanceof FileSource && canonical(((FileSource) other).shp).equals(canonical(shp));
        }
    }

    // ------------------------------------------------------------------ inside a zip package

    /**
     * The layers in a zip package: one per .shp in it.
     *
     * @throws IOException when the file is not a readable zip
     */
    public static List<ShapeSource> zip(File zip) throws IOException {
        List<ShapeSource> out = new ArrayList<>();
        Charset names = namesOf(zip);
        try (ZipFile z = new ZipFile(zip, names)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            List<String> shps = new ArrayList<>();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!e.isDirectory() && n.toLowerCase(Locale.ROOT).endsWith(".shp") && !n.startsWith("__MACOSX/")) {
                    shps.add(n);
                }
            }
            Collections.sort(shps);
            for (String n : shps) {
                out.add(new ZipSource(zip, names, n));
            }
        }
        return out;
    }

    /** UTF-8 entry names, else CP437 (old zip tools); an entry name that is not UTF-8 makes ZipFile fail. */
    private static Charset namesOf(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                en.nextElement();
            }
            return StandardCharsets.UTF_8;
        } catch (IllegalArgumentException | ZipException e) {
            if (e instanceof ZipException && !String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("malformed")
                    && !String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("invalid")) {
                throw e;
            }
            return Charset.forName("IBM437");
        }
    }

    private static final class ZipSource extends ShapeSource {
        private final File zip;
        private final Charset names;
        private final String shpEntry;
        private final String prefix;                       // the entry path without ".shp"

        ZipSource(File zip, Charset names, String shpEntry) {
            super(base(shpEntry.substring(shpEntry.lastIndexOf('/') + 1)));
            this.zip = zip;
            this.names = names;
            this.shpEntry = shpEntry;
            this.prefix = shpEntry.substring(0, shpEntry.length() - 4);
        }

        @Override
        public String name() {
            return zip.getPath() + "!" + shpEntry;
        }

        @Override
        public String shortName() {
            return zip.getName() + "!" + shpEntry;
        }

        @Override
        public InputStream open(String ext) throws IOException {
            ZipFile z = new ZipFile(zip, names);
            ZipEntry e = z.getEntry(prefix + ext.toLowerCase(Locale.ROOT));
            if (e == null) {
                e = z.getEntry(prefix + ext.toUpperCase(Locale.ROOT));
            }
            if (e == null) {
                // any case: e.g. Towns.SHP with towns.dbf
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements() && e == null) {
                    ZipEntry x = en.nextElement();
                    if (x.getName().equalsIgnoreCase(prefix + ext)) {
                        e = x;
                    }
                }
            }
            if (e == null) {
                z.close();
                return null;
            }
            InputStream in = z.getInputStream(e);
            return new FilterInputStream(in) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        z.close();
                    }
                }
            };
        }

        @Override
        public long stamp() {
            return ((zip.length() * 31 + zip.lastModified()) * 31) + shpEntry.hashCode();
        }

        @Override
        public boolean sameAs(ShapeSource other) {
            return other instanceof ZipSource && canonical(((ZipSource) other).zip).equals(canonical(zip))
                    && ((ZipSource) other).shpEntry.equals(shpEntry);
        }
    }

    // ------------------------------------------------------------------ helpers

    static String base(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    @Override
    public String toString() {
        return name();
    }
}
