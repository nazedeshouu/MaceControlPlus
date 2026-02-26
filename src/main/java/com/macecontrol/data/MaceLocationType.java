package com.macecontrol.data;

/**
 * Enumerates the types of locations where a tracked mace may reside.
 *
 * <p>The location type is stored in the database alongside coordinates and
 * holder/container information to provide precise location context without
 * requiring a full world scan to interpret a stored record.</p>
 */
public enum MaceLocationType {

    /** The mace is inside an online player's main inventory. */
    PLAYER_INVENTORY,

    /** The mace is inside an online player's ender chest. */
    PLAYER_ENDERCHEST,

    /** The mace is inside a placed container block (chest, barrel, etc.). */
    CONTAINER,

    /** The mace is a dropped item entity on the ground. */
    GROUND_ENTITY,

    /** The mace is displayed in an item frame. */
    ITEM_FRAME,

    /** The mace is inside a placed hopper block. */
    HOPPER,

    /** The mace is inside a placed dropper block. */
    DROPPER,

    /** The mace is inside a placed dispenser block. */
    DISPENSER,

    /** The mace is inside a minecart with chest. */
    MINECART_CHEST,

    /** The mace is inside a minecart with hopper. */
    MINECART_HOPPER,

    /**
     * The mace is inside a shulker box that is itself stored as an item
     * (e.g. inside a chest or a player's inventory).
     */
    SHULKER_ITEM,

    /** The mace is inside a bundle item carried by a player or stored in a container. */
    BUNDLE,

    /**
     * The mace is believed to be in the possession of an offline player based
     * on their last-seen inventory data.
     */
    OFFLINE_PLAYER,

    /** The location type is not known or could not be determined. */
    UNKNOWN;

    /**
     * Parses a {@link MaceLocationType} from a string in a null-safe, case-insensitive manner.
     *
     * @param s the string to parse
     * @return the matching {@link MaceLocationType}, or {@link #UNKNOWN} if not recognised
     */
    public static MaceLocationType fromString(String s) {
        if (s == null || s.isBlank()) {
            return UNKNOWN;
        }
        try {
            return MaceLocationType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
