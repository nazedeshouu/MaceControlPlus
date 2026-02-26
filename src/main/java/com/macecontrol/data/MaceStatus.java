package com.macecontrol.data;

/**
 * Represents the lifecycle status of a tracked mace in the registry.
 *
 * <ul>
 *   <li>{@link #ACTIVE}    – The mace is in circulation and tracked.</li>
 *   <li>{@link #MISSING}   – The mace has not been located for one or more
 *                             consecutive scans but has not yet been declared destroyed.</li>
 *   <li>{@link #DESTROYED} – The mace has been confirmed destroyed (lava, void, despawn,
 *                             explosion, etc.) or missed enough consecutive accessible scans.
 *                             Its slot is freed.</li>
 *   <li>{@link #REVOKED}   – The mace was administratively removed via {@code /mace revoke}.
 *                             Its slot is freed.</li>
 * </ul>
 */
public enum MaceStatus {

    /** The mace is active and in circulation. */
    ACTIVE,

    /** The mace is temporarily missing from its expected location. */
    MISSING,

    /** The mace has been confirmed destroyed; its slot is now free. */
    DESTROYED,

    /** The mace was administratively revoked; its slot is now free. */
    REVOKED;

    /**
     * Parses a {@link MaceStatus} from a string in a null-safe, case-insensitive manner.
     *
     * @param s the string to parse
     * @return the matching {@link MaceStatus}, or {@code null} if not recognised
     */
    public static MaceStatus fromString(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return MaceStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
