package com.macecontrol.data;

/**
 * Represents the enchantment tier of a tracked mace.
 *
 * <ul>
 *   <li>{@link #LIMITED} – standard tier, obtainable through player crafting.
 *       Restricted to the whitelist of allowed enchantments defined in config.</li>
 *   <li>{@link #FULL} – legendary tier, only obtainable via {@code /mace give}.
 *       Permits all mace enchantments with no restrictions.</li>
 * </ul>
 */
public enum MaceTier {

    /** Standard mace tier, player-craftable, enchantment-restricted. */
    LIMITED,

    /** Legendary mace tier, admin-given only, no enchantment restrictions. */
    FULL;

    /**
     * Parses a {@link MaceTier} from a string in a null-safe, case-insensitive manner.
     *
     * @param s the string to parse (e.g. {@code "LIMITED"}, {@code "full"})
     * @return the matching {@link MaceTier}, or {@code null} if the string is null,
     *         blank, or does not match any known tier
     */
    public static MaceTier fromString(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return MaceTier.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
