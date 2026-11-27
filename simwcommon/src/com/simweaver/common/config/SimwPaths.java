package com.simweaver.common.config;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The server's folders.
 *
 * <ul>
 *   <li><b>program</b> (0.14.5): the unpacked release, found from the jar ({@code <program>/lib/*.jar}); what a
 *       release delivers and an update replaces: the web pages, the FOMs, SISO-REF-010, the RPR table, the
 *       templates ({@code etc.dist}) and the samples ({@code junctions.dist}).</li>
 *   <li><b>root</b> (0.14.5): the <i>site</i> folder of one running server: {@code root=} on the command line,
 *       else the environment variable {@code JUNCTION_SITE}, else the program folder (the simple way: unzip and
 *       run). Every relative path in a setting is relative to it ({@link #site(String)}).</li>
 *   <li><b>etc</b> (0.11.4): the configuration folder, {@code <root>/etc} unless {@code etcdir} names another.
 *       Every default that names a configuration file or folder is taken from here.</li>
 * </ul>
 *
 * Delivered files a site may override (FOM modules, SISO-REF-010, the RPR table, maps) are looked for in the
 * site first, then in the program folder ({@link #fomRoots}, {@link #findDelivered}). The server sets the folders
 * once at start ({@link #setFolders}); until then, and in tests, all three are the working folder (and its
 * {@code etc}), as before 0.14.5.
 */
public final class SimwPaths {

    /** The default configuration folder: {@code etc}, relative to the root. */
    public static final Path DEFAULT_ETC = Paths.get("etc");

    /** The environment variable naming the site folder when {@code root=} is not given. */
    public static final String SITE_VARIABLE = "JUNCTION_SITE";

    private static volatile Path program = Paths.get("").toAbsolutePath().normalize();
    private static volatile Path root = program;
    private static volatile Path etc = DEFAULT_ETC;
    private static volatile boolean moved;

    private SimwPaths() {
    }

    // ---- the three folders ----

    /** @return the program folder (absolute) */
    public static Path program() {
        return program;
    }

    /** @return the site root (absolute) */
    public static Path root() {
        return root;
    }

    /** @return true when the site is a folder of its own, not the program folder ({@code root=} / JUNCTION_SITE) */
    public static boolean isSite() {
        return !root.equals(program);
    }

    /** @return the configuration folder: absolute once the server has set the folders, {@code etc} before */
    public static Path etc() {
        return etc;
    }

    /** @return a file or folder in the configuration folder, e.g. {@code etc("j2j", "network.key")} */
    public static Path etc(String first, String... more) {
        Path p = etc.resolve(first);
        for (String m : more) {
            p = p.resolve(m);
        }
        return p;
    }

    /** The same as a string, for defaults of settings. */
    public static String etcString(String first, String... more) {
        return etc(first, more).toString();
    }

    /** @return the configuration folder as an absolute path (against the root) */
    public static Path etcAbsolute() {
        return root.resolve(etc).normalize();
    }

    /** @return true when the configuration folder is not {@code etc} in the root (etcdir) */
    public static boolean isMoved() {
        return moved;
    }

    /** @return a path in the program folder, e.g. {@code delivered("fom", "ieee1516-2010")} */
    public static Path delivered(String first, String... more) {
        Path p = program.resolve(first);
        for (String m : more) {
            p = p.resolve(m);
        }
        return p;
    }

    /**
     * A path from a setting: absolute as it is, relative against the site root (0.14.5: the one rule for paths
     * in settings). Empty gives the root.
     */
    public static Path site(String path) {
        if (path == null || path.trim().isEmpty()) {
            return root;
        }
        Path p;
        try {
            p = Paths.get(path.trim());
        } catch (InvalidPathException e) {
            return root.resolve(path.trim().replaceAll("[\\x00:*?\"<>|]", "_")).normalize();
        }
        return p.isAbsolute() ? p.normalize() : root.resolve(p).normalize();
    }

    /** {@link #site(String)} as a file. */
    public static File siteFile(String path) {
        return site(path).toFile();
    }

    // ---- setting them ----

    /** Sets all three (the server, once at start). {@code etc} relative to the root or absolute; kept absolute. */
    public static void setFolders(Path programFolder, Path rootFolder, Path etcFolder, boolean etcMoved) {
        program = programFolder.toAbsolutePath().normalize();
        root = (rootFolder == null ? program : rootFolder.toAbsolutePath()).normalize();
        etc = root.resolve(etcFolder == null ? DEFAULT_ETC : etcFolder).normalize();      // absolute from here on
        moved = etcFolder != null && etcMoved;
    }

    /** Back to the working folder for everything (tests). */
    public static void reset() {
        program = Paths.get("").toAbsolutePath().normalize();
        root = program;
        etc = DEFAULT_ETC;
        moved = false;
    }

    /** Sets the configuration folder (null: back to {@code etc}). */
    public static void setEtc(Path p) {
        setEtc(p, p != null && !p.normalize().equals(DEFAULT_ETC));
    }

    /** Sets the configuration folder, and whether it is another than the root's {@code etc}. */
    public static void setEtc(Path p, boolean isMoved) {
        etc = p == null ? DEFAULT_ETC : p;
        moved = p != null && isMoved;
    }

    // ---- delivered content: the site first, then the program ----

    /**
     * The folders a FOM module of a standard is looked for in: the explicit root of a setting
     * ({@code endpoint.hla.fomrootdir}, {@code datasource.hla.customfomrootdir}) alone, else
     * {@code <etc>/fom/<standard>} then {@code <program>/fom/<standard>} (one when they are the same).
     *
     * @param standardFolder {@code ieee1516-2010} or {@code ieee1516-2025}
     * @param explicitRoot   the setting's value (its standard folder is added), or null / empty for the default
     */
    public static List<Path> fomRoots(String standardFolder, String explicitRoot) {
        List<Path> l = new ArrayList<>();
        if (explicitRoot != null && !explicitRoot.trim().isEmpty()) {
            l.add(site(explicitRoot).resolve(standardFolder).normalize());
            return Collections.unmodifiableList(l);
        }
        Path own = etcAbsolute().resolve("fom").resolve(standardFolder).normalize();
        Path delivered = delivered("fom", standardFolder).normalize();
        l.add(own);
        if (!delivered.equals(own)) {
            l.add(delivered);
        }
        return Collections.unmodifiableList(l);
    }

    /**
     * A FOM module or folder named in a setting: absolute as it is; else the first of {@code roots} that has it;
     * else {@code base} + name when that exists (names relative to the root, as before 0.14.5); else the name in
     * the first root (so a message names where it was expected).
     */
    public static File findFom(List<Path> roots, String name, File base) {
        File f = new File(name);
        if (f.isAbsolute()) {
            return f;
        }
        for (Path r : roots) {
            File c = r.resolve(name).toFile();
            if (c.exists()) {
                return c;
            }
        }
        if (base != null) {
            File rel = new File(base, name);
            if (rel.exists()) {
                return rel;
            }
        }
        return roots.isEmpty() ? f : roots.get(0).resolve(name).toFile();
    }

    /** @return the site's file when it exists, else the program's */
    public static Path findDelivered(Path inSite, Path inProgram) {
        return Files.exists(inSite) || inProgram == null || !Files.exists(inProgram) ? inSite : inProgram;
    }

    // ---- finding the program folder ----

    /**
     * The program folder from where a class was loaded: {@code <program>/lib/<x>.jar} gives {@code <program>}
     * (links followed), a jar elsewhere its folder; classes not from a jar (an IDE, {@code out/classes}) the
     * working folder.
     */
    public static Path detectProgram(Class<?> c) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        try {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            URL u = cs == null ? null : cs.getLocation();
            if (u == null || !"file".equalsIgnoreCase(u.getProtocol())) {
                return cwd;
            }
            Path p = Paths.get(u.toURI());
            if (!Files.isRegularFile(p) || !p.getFileName().toString().toLowerCase().endsWith(".jar")) {
                return cwd;
            }
            try {
                p = p.toRealPath();
            } catch (IOException e) {
                p = p.toAbsolutePath();
            }
            Path dir = p.getParent();
            if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("lib")
                    && dir.getParent() != null) {
                return dir.getParent().normalize();
            }
            return dir == null ? cwd : dir.normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return cwd;
        }
    }

    /**
     * The site root: {@code root} from the command line, else the variable's value, else the program folder.
     * A relative root is relative to the working folder.
     */
    public static Path chooseRoot(String fromArgs, String fromEnvironment, Path programFolder) {
        String r = fromArgs != null && !fromArgs.trim().isEmpty() ? fromArgs.trim()
                : fromEnvironment != null && !fromEnvironment.trim().isEmpty() ? fromEnvironment.trim() : null;
        return r == null ? programFolder.toAbsolutePath().normalize() : Paths.get(r).toAbsolutePath().normalize();
    }
}
