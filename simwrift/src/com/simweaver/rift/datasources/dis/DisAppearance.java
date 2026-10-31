package com.simweaver.rift.datasources.dis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Appearance and capability bits of the Entity State PDU mapped to RPR attributes, as a table.
 *
 * <p>Bit positions follow SISO-REF-010 (DIS enumerations) for the general platform
 * appearance (land, air, surface) and the life form appearance; they are the same in DIS 6 and
 * DIS 7 for the bits used here. Bits whose meaning depends on more than kind and domain are
 * not mapped. Frozen (bit 21) goes into Spatial, State (bit 23, deactivated) removes the
 * entity: see {@link DisEntityState}.
 */
public final class DisAppearance {

    /** Any domain. */
    private static final int ANY = -1;

    private enum Kind { BOOLEAN, ENUM32 }

    private static final class Rule {
        final int entityKind;
        final int domain;
        final String attribute;
        final int from;
        final int width;
        final Kind type;

        Rule(int entityKind, int domain, String attribute, int from, int width, Kind type) {
            this.entityKind = entityKind;
            this.domain = domain;
            this.attribute = attribute;
            this.from = from;
            this.width = width;
            this.type = type;
        }

        boolean applies(DisEntityType t) {
            return (entityKind == ANY || t.kind == entityKind) && (domain == ANY || t.domain == domain);
        }
    }

    private static final int PLATFORM = DisEntityType.KIND_PLATFORM;
    private static final int LIFE_FORM = DisEntityType.KIND_LIFE_FORM;
    private static final int LAND = DisEntityType.DOMAIN_LAND;
    private static final int AIR = DisEntityType.DOMAIN_AIR;
    private static final int SURFACE = DisEntityType.DOMAIN_SURFACE;

    private static final List<Rule> RULES;

    static {
        List<Rule> r = new ArrayList<>();
        // all platforms
        r.add(new Rule(PLATFORM, ANY, "Immobilized", 1, 1, Kind.BOOLEAN));            // mobility / propulsion kill
        r.add(new Rule(PLATFORM, ANY, "TrailingEffectsCode", 7, 2, Kind.ENUM32));
        r.add(new Rule(PLATFORM, ANY, "FlamesPresent", 15, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, ANY, "PowerPlantOn", 22, 1, Kind.BOOLEAN));
        // land platforms
        r.add(new Rule(PLATFORM, LAND, "FirePowerDisabled", 2, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "HatchState", 9, 3, Kind.ENUM32));
        r.add(new Rule(PLATFORM, LAND, "HeadLightsOn", 12, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "TailLightsOn", 13, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "BrakeLightsOn", 14, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "LauncherRaised", 16, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "IsConcealed", 19, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "TentDeployed", 24, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "RampDeployed", 25, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "BlackOutLightsOn", 26, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "BlackOutBrakeLightsOn", 27, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "SpotLightsOn", 28, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, LAND, "InteriorLightsOn", 29, 1, Kind.BOOLEAN));
        // air platforms
        r.add(new Rule(PLATFORM, AIR, "LandingLightsOn", 12, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, AIR, "NavigationLightsOn", 13, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, AIR, "AntiCollisionLightsOn", 14, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, AIR, "AfterburnerOn", 16, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, AIR, "FormationLightsOn", 24, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, AIR, "SpotLightsOn", 28, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, AIR, "InteriorLightsOn", 29, 1, Kind.BOOLEAN));
        // surface platforms
        r.add(new Rule(PLATFORM, SURFACE, "FirePowerDisabled", 2, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, SURFACE, "RunningLightsOn", 12, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, SURFACE, "SpotLightsOn", 28, 1, Kind.BOOLEAN));
        r.add(new Rule(PLATFORM, SURFACE, "InteriorLightsOn", 29, 1, Kind.BOOLEAN));
        // life forms
        r.add(new Rule(LIFE_FORM, ANY, "ComplianceState", 5, 4, Kind.ENUM32));
        r.add(new Rule(LIFE_FORM, ANY, "FlashLightsOn", 12, 1, Kind.BOOLEAN));
        r.add(new Rule(LIFE_FORM, ANY, "StanceCode", 16, 4, Kind.ENUM32));
        r.add(new Rule(LIFE_FORM, ANY, "PrimaryWeaponState", 24, 2, Kind.ENUM32));
        r.add(new Rule(LIFE_FORM, ANY, "SecondaryWeaponState", 26, 2, Kind.ENUM32));
        RULES = Collections.unmodifiableList(r);
    }

    private DisAppearance() {
    }

    /** Adds the appearance and capability attributes of this state to {@code out}. */
    public static void put(DisEntityState s, Map<String, byte[]> out) {
        DisEntityType t = s.type;
        if (t.kind != DisEntityType.KIND_ENVIRONMENTAL && t.kind != DisEntityType.KIND_OTHER) {
            out.put("DamageState", RprCodec.u32(s.appearanceBits(3, 2)));         // damage / life form health
        }
        if (t.kind == PLATFORM) {
            int smoke = s.appearanceBits(5, 2);                                     // 1 plume, 2 engine smoke, 3 both
            out.put("SmokePlumePresent", RprCodec.bool((smoke & 1) != 0));
            out.put("EngineSmokeOn", RprCodec.bool((smoke & 2) != 0));
            if (t.domain == LAND) {
                // paint scheme (bit 0) and camouflage type (bits 17-18) -> CamouflageEnum32
                int camo = s.appearanceBits(0, 1) == 0 ? 0 : 1 + s.appearanceBits(17, 2);
                out.put("CamouflageType", RprCodec.u32(camo));
            }
            // capabilities (land platform bit layout; the first four bits are the same for all platforms)
            out.put("HasAmmunitionSupplyCap", RprCodec.bool((s.capabilities & 0x1) != 0));
            out.put("HasFuelSupplyCap", RprCodec.bool((s.capabilities & 0x2) != 0));
            out.put("HasRecoveryCap", RprCodec.bool((s.capabilities & 0x4) != 0));
            out.put("HasRepairCap", RprCodec.bool((s.capabilities & 0x8) != 0));
        }
        if (t.kind == DisEntityType.KIND_CULTURAL_FEATURE) {
            out.put("FlamesPresent", RprCodec.bool(s.appearanceBits(15, 1) != 0));
        }
        for (Rule r : RULES) {
            if (!r.applies(t)) continue;
            int v = s.appearanceBits(r.from, r.width);
            out.put(r.attribute, r.type == Kind.BOOLEAN ? RprCodec.bool(v != 0) : RprCodec.u32(v));
        }
    }
}
