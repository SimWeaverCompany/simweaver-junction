package com.simweaver.common.hla.session;

import com.simweaver.common.hla.RtiAvailability;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Where the classes of an HLA RTI come from, and the factory of {@link RtiSession}s on it.
 *
 * <ul>
 *   <li>{@link #classPath()}: the RTI on the application's class path (the {@code RTI_JAR} of
 *       the start script), as Rift uses it.</li>
 *   <li>{@link #isolated(List)}: the RTI in the given jars, in its own class loader. Its parent
 *       is the JDK's platform class loader, so nothing of an RTI on the class path is visible
 *       to it and the reverse. The session implementations
 *       ({@code com.simweaver.common.hla.session.impl1516e/impl2025}) are defined again inside
 *       it, so that they link against <em>that</em> library's API classes; every other
 *       {@code com.simweaver} class (the {@link RtiSession} interface first of all) comes from
 *       the application, which is how the two sides talk. This allows several RTI vendors
 *       and versions in one process, e.g. to bridge between them.</li>
 * </ul>
 *
 * Isolated libraries are shared by their jar list: endpoints naming the same jars use the same
 * loader (one RTI instance per library). Thread safe.
 */
public final class RtiLibrary {

    private static final String IMPL_1516E = "com.simweaver.common.hla.session.impl1516e.Rti1516eSession";
    private static final String IMPL_2025 = "com.simweaver.common.hla.session.impl2025.Rti2025Session";
    private static final String[] IMPL_PACKAGES = {
        "com.simweaver.common.hla.session.impl1516e.", "com.simweaver.common.hla.session.impl2025."};

    private static final RtiLibrary CLASS_PATH = new RtiLibrary(Collections.<File>emptyList(), null);
    private static final Map<List<String>, RtiLibrary> ISOLATED = new ConcurrentHashMap<List<String>, RtiLibrary>();

    private final List<File> jars;          // empty for the class path
    private final ClassLoader loader;       // null for the class path
    private final boolean has1516e;
    private final boolean has2025;
    private final Object connectLock = new Object();

    private RtiLibrary(List<File> jars, ClassLoader loader) {
        this.jars = Collections.unmodifiableList(new ArrayList<File>(jars));
        this.loader = loader;
        if (loader == null) {
            this.has1516e = RtiAvailability.HLA_EVOLVED;
            this.has2025 = RtiAvailability.HLA_4;
        } else {
            this.has1516e = contains(jars, "hla/rti1516e/RTIambassador.class");
            this.has2025 = contains(jars, "hla/rti1516_2025/RTIambassador.class");
        }
    }

    /** @return the RTI on the application's class path */
    public static RtiLibrary classPath() {
        return CLASS_PATH;
    }

    /**
     * @param jarsOrFolders jar files and folders (every {@code *.jar} directly inside is used)
     * @return the isolated library of these jars (shared by equal jar lists)
     * @throws IllegalArgumentException when an entry does not exist or no jar is found
     */
    public static RtiLibrary isolated(List<File> jarsOrFolders) {
        List<File> jars = expand(jarsOrFolders);
        if (jars.isEmpty()) {
            throw new IllegalArgumentException("no RTI jar found in " + jarsOrFolders);
        }
        List<String> key = new ArrayList<String>();
        for (File f : jars) {
            key.add(canonical(f));
        }
        return ISOLATED.computeIfAbsent(key, k -> {
            List<URL> urls = new ArrayList<URL>();
            for (File f : jars) {
                try {
                    urls.add(f.toURI().toURL());
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException("bad jar path " + f, e);
                }
            }
            ClassLoader app = RtiLibrary.class.getClassLoader();
            return new RtiLibrary(jars, new IsolatingLoader(urls.toArray(new URL[0]), app, jars.toString()));
        });
    }

    /**
     * From a setting such as {@code endpoint.hla.rtijar=lib/rti/prti5;extra.jar}.
     *
     * @param setting ';'-separated jars and folders; null or blank = {@link #classPath()}
     * @param baseDir folder that relative entries are relative to (null = working directory)
     * @return the library
     * @throws IllegalArgumentException when an entry does not exist or no jar is found
     */
    public static RtiLibrary fromSetting(String setting, File baseDir) {
        if (setting == null || setting.trim().isEmpty()) {
            return classPath();
        }
        List<File> files = new ArrayList<File>();
        for (String part : setting.split("[;,]")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            File f = new File(p);
            if (!f.isAbsolute() && baseDir != null) {
                f = new File(baseDir, p);
            }
            files.add(f);
        }
        return files.isEmpty() ? classPath() : isolated(files);
    }

    /** @return true for the class path library */
    public boolean isClassPath() {
        return loader == null;
    }

    /** @return the jars of an isolated library (empty for the class path) */
    public List<File> getJars() {
        return jars;
    }

    /** @return "class path", or the jar names */
    public String describe() {
        if (loader == null) {
            return "class path";
        }
        StringBuilder sb = new StringBuilder();
        for (File f : jars) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(f.getPath());
        }
        return sb.toString();
    }

    /**
     * Checked without loading anything of the RTI.
     *
     * @param version the HLA version
     * @return true when this library holds that version's RTI API
     */
    public boolean isAvailable(RtiSession.Version version) {
        return version == RtiSession.Version.HLA_1516E ? has1516e : has2025;
    }

    /** @return why {@link #isAvailable} is false, for messages; null when available */
    public String unavailableReason(RtiSession.Version version) {
        if (isAvailable(version)) {
            return null;
        }
        if (loader == null) {
            return "No HLA RTI library for " + version.label() + " (" + version.apiPackage()
                    + ") on the class path. Set RTI_JAR in the start script, or endpoint.hla.rtijar in the configuration.";
        }
        return "The RTI library " + describe() + " has no " + version.label() + " API (" + version.apiPackage() + ").";
    }

    /**
     * @param version the HLA version
     * @return a new, unconnected session on this library
     * @throws RtiSessionException {@code RtiNotAvailable} when the API is missing or incomplete
     */
    /**
     * The lock to hold around connect, create and join calls on this library's RTI.
     *
     * <p>Several federates in one process share the RTI's local library: endpoints of a gateway
     * open in parallel, a group starts its gateways, Rift creates datasources. An RTI's local
     * library need not take two connects or joins at the same moment: it can fail inside the
     * RTI (e.g. a ConcurrentModificationException while a FOM is parsed). Holding this lock
     * makes them one at a time; everything after the join stays parallel. One lock per library
     * (the class path RTI, both APIs, is one library).
     */
    public Object connectLock() {
        return connectLock;
    }

    public RtiSession createSession(RtiSession.Version version) throws RtiSessionException {
        String reason = unavailableReason(version);
        if (reason != null) {
            throw new RtiSessionException("RtiNotAvailable", reason, null);
        }
        String impl = version == RtiSession.Version.HLA_1516E ? IMPL_1516E : IMPL_2025;
        ClassLoader cl = loader != null ? loader : RtiLibrary.class.getClassLoader();
        try {
            Class<?> c = Class.forName(impl, true, cl);
            return (RtiSession) c.getConstructor(String.class).newInstance(describe());
        } catch (LinkageError e) {
            throw new RtiSessionException("RtiNotAvailable", "The RTI library " + describe() + " is incomplete: " + e, e);
        } catch (ReflectiveOperationException e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            throw new RtiSessionException("RtiNotAvailable", "Cannot create a session on " + describe() + ": " + t, t);
        }
    }

    @Override
    public String toString() {
        return "RtiLibrary[" + describe() + "]";
    }

    // ------------------------------------------------------------------ jars

    private static List<File> expand(List<File> entries) {
        Set<File> out = new LinkedHashSet<File>();
        for (File f : entries) {
            if (f.isDirectory()) {
                File[] list = f.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
                if (list != null) {
                    Arrays.sort(list);
                    out.addAll(Arrays.asList(list));
                }
            } else if (f.isFile()) {
                out.add(f);
            } else {
                throw new IllegalArgumentException("RTI library entry not found: " + f.getPath());
            }
        }
        return new ArrayList<File>(out);
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    /** True when one of the jars (or a jar named in their manifest Class-Path) has the entry. */
    private static boolean contains(List<File> jars, String entry) {
        Set<String> seen = new LinkedHashSet<String>();
        List<File> todo = new ArrayList<File>(jars);
        while (!todo.isEmpty()) {
            File f = todo.remove(0);
            if (!seen.add(canonical(f)) || !f.isFile()) {
                continue;
            }
            try (JarFile jf = new JarFile(f)) {
                if (jf.getEntry(entry) != null) {
                    return true;
                }
                Manifest m = jf.getManifest();
                String cp = m == null ? null : m.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (cp != null) {
                    for (String p : cp.trim().split("\\s+")) {
                        if (!p.isEmpty() && !p.contains(":")) {
                            todo.add(new File(f.getParentFile(), p.replace('/', File.separatorChar)));
                        }
                    }
                }
            } catch (IOException e) {
                // not a jar: skip
            }
        }
        return false;
    }

    /**
     * Child-first for everything but SimWeaver: the platform loader (JDK), then the jars. The
     * session implementations are defined here from the application's class files; other
     * {@code com.simweaver} classes are taken from the application.
     */
    private static final class IsolatingLoader extends URLClassLoader {

        static {
            ClassLoader.registerAsParallelCapable();
        }

        private final ClassLoader app;
        private final String label;

        IsolatingLoader(URL[] urls, ClassLoader app, String label) {
            super(urls, ClassLoader.getPlatformClassLoader());
            this.app = app;
            this.label = label;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (isImpl(name)) {
                        c = defineFromApplication(name);
                    } else if (name.startsWith("com.simweaver.")) {
                        c = app.loadClass(name);
                    } else {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        private static boolean isImpl(String name) {
            for (String p : IMPL_PACKAGES) {
                if (name.startsWith(p)) {
                    return true;
                }
            }
            return false;
        }

        private Class<?> defineFromApplication(String name) throws ClassNotFoundException {
            String res = name.replace('.', '/') + ".class";
            try (InputStream in = app.getResourceAsStream(res)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                byte[] b = out.toByteArray();
                return defineClass(name, b, 0, b.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        public String toString() {
            return "RtiLibrary loader " + label;
        }
    }
}
