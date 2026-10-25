package com.simweaver.common.iem;

/**
 * Options controlling how FOM modules are merged into an {@link InformationExchangeModel}.
 * Fluent and mutable; the model copies the values it needs, so one instance can be reused.
 *
 * <pre>
 * IemOptions opt = new IemOptions().setStrictReferences(false);
 * InformationExchangeModel iem = InformationExchangeModel.fromHlaEvolved(foms, opt);
 * </pre>
 */
public final class IemOptions {

    private boolean includeStandardMim = true;
    private String standardMim;
    private boolean strictReferences = true;
    private boolean extendedMerging = true;
    private boolean warningsAsErrors;

    /** Default options. */
    public IemOptions() {
    }

    /** Copy constructor. */
    public IemOptions(IemOptions other) {
        this.includeStandardMim = other.includeStandardMim;
        this.standardMim = other.standardMim;
        this.strictReferences = other.strictReferences;
        this.extendedMerging = other.extendedMerging;
        this.warningsAsErrors = other.warningsAsErrors;
    }

    public boolean isIncludeStandardMim() {
        return includeStandardMim;
    }

    /**
     * Whether the standard MIM is merged in first (default true), exactly as an RTI does when it
     * builds the FDD. It provides HLAobjectRoot (with HLAprivilegeToDeleteObject), HLAinteractionRoot
     * and the predefined HLA datatypes (HLAinteger32BE, HLAASCIIstring, HLAboolean, ...), which FOM
     * modules such as RPR and NETN reference without defining.
     */
    public IemOptions setIncludeStandardMim(boolean includeStandardMim) {
        this.includeStandardMim = includeStandardMim;
        return this;
    }

    public String getStandardMim() {
        return standardMim;
    }

    /**
     * Supplies the MIM to use instead of the built-in one, as a path or as XML text (same rules as the
     * FOM list). Supply the official {@code HLAstandardMIM.xml} of your RTI to get the full
     * HLAmanager (MOM) class tree; the built-in MIM only contains the roots and predefined datatypes.
     * Must be of the same HLA version as the FOM list. Null restores the built-in MIM.
     */
    public IemOptions setStandardMim(String pathOrXml) {
        this.standardMim = pathOrXml;
        return this;
    }

    public boolean isStrictReferences() {
        return strictReferences;
    }

    /**
     * When true (default) every reference must resolve: datatypes, transportations, dimensions,
     * variant enumerators, simple/enumerated representations. Unresolved references are errors.
     * When false they are recorded as warnings and the corresponding getters return null.
     */
    public IemOptions setStrictReferences(boolean strictReferences) {
        this.strictReferences = strictReferences;
        return this;
    }

    public boolean isExtendedMerging() {
        return extendedMerging;
    }

    /**
     * When true (default) modules may extend what other modules define, as specified for HLA 4
     * (IEEE 1516-2025 "extended merging") and supported for HLA Evolved by current RTIs: add
     * attributes/parameters/dimensions to an existing class, enumerators to an existing enumeration,
     * and alternatives to an existing variant record. NETN v4 relies on this, also in its HLA Evolved
     * modules.
     * <p>
     * When false the classic IEEE 1516-2010 rule applies: a class with members, an enumeration or a
     * variant record defined in several modules must be defined identically everywhere (or be
     * scaffolding).
     */
    public IemOptions setExtendedMerging(boolean extendedMerging) {
        this.extendedMerging = extendedMerging;
        return this;
    }

    public boolean isWarningsAsErrors() {
        return warningsAsErrors;
    }

    /** When true, any warning makes the build fail with an {@link IemException} (default false). */
    public IemOptions setWarningsAsErrors(boolean warningsAsErrors) {
        this.warningsAsErrors = warningsAsErrors;
        return this;
    }
}
