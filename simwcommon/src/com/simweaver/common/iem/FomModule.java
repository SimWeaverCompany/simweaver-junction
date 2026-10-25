package com.simweaver.common.iem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One FOM module that was merged into an {@link InformationExchangeModel}, with its identification
 * table. Modules are listed in merge order; the standard MIM (built-in or supplied) is always first.
 */
public final class FomModule {

    private final int index;
    private final String label;
    private final String location;
    private final boolean standardMim;
    private final boolean builtIn;
    private final boolean namespaceDeclared;
    private final FomFamily family;
    private final Map<String, String> identification;
    private final List<String> dependencies;
    private final Map<String, String> notes;

    FomModule(int index, String label, String location, boolean standardMim, boolean builtIn,
            boolean namespaceDeclared, Map<String, String> identification, List<String> dependencies,
            Map<String, String> notes) {
        this.index = index;
        this.label = label;
        this.location = location;
        this.standardMim = standardMim;
        this.builtIn = builtIn;
        this.namespaceDeclared = namespaceDeclared;
        this.identification = Collections.unmodifiableMap(identification);
        this.dependencies = Collections.unmodifiableList(dependencies);
        this.notes = Collections.unmodifiableMap(notes);
        this.family = standardMim ? FomFamily.STANDARD_MIM : FomFamily.classify(getName(), getVersion());
    }

    /** Position in the merge order (0 = standard MIM when it is included). */
    public int getIndex() {
        return index;
    }

    /** Short label used in diagnostics: the file name, {@code <inline #n>} or {@code <built-in MIM>}. */
    public String getLabel() {
        return label;
    }

    /** Absolute file path, or null when the module was given as inline XML or is built in. */
    public String getLocation() {
        return location;
    }

    /** True when the module was supplied as XML text rather than a path. */
    public boolean isInline() {
        return location == null && !builtIn;
    }

    /** True for the standard Management Object Model (MIM). */
    public boolean isStandardMim() {
        return standardMim;
    }

    /** True for the MIM shipped with this package (as opposed to one supplied through {@link IemOptions}). */
    public boolean isBuiltIn() {
        return builtIn;
    }

    /** False when the document had no OMT XML namespace and its version was assumed. */
    public boolean isNamespaceDeclared() {
        return namespaceDeclared;
    }

    /** Recognised FOM family (RPR v2/v3, NETN, MIM, other). */
    public FomFamily getFamily() {
        return family;
    }

    /** modelIdentification/name, or null. */
    public String getName() {
        return identification.get("name");
    }

    /** modelIdentification/type (FOM, SOM), or null. */
    public String getType() {
        return identification.get("type");
    }

    /** modelIdentification/version, or null. */
    public String getVersion() {
        return identification.get("version");
    }

    /** modelIdentification/modificationDate, or null. */
    public String getModificationDate() {
        return identification.get("modificationDate");
    }

    /** modelIdentification/description, or null. */
    public String getDescription() {
        return identification.get("description");
    }

    /** All simple-text modelIdentification entries (name, type, version, purpose, ...), in document order. */
    public Map<String, String> getIdentification() {
        return identification;
    }

    /** Identifications of references of type "Dependency", whitespace normalised. */
    public List<String> getDependencies() {
        return dependencies;
    }

    /** Notes table of this module: label to text. Notes are module local and never merged. */
    public Map<String, String> getNotes() {
        return notes;
    }

    @Override
    public String toString() {
        String n = getName();
        return label + (n != null && !n.equals(label) ? " (" + n + (getVersion() != null ? " v" + getVersion() : "")
                + ")" : "");
    }
}
