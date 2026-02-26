package com.macecontrol.data;

import java.util.UUID;

/**
 * Immutable data transfer object representing a single row in the
 * {@code mace_audit_log} database table.
 *
 * <p>Every significant mace lifecycle event (craft, pick-up, drop, enchant block,
 * destruction, duplication, etc.) produces one {@link AuditEntry} that is persisted
 * asynchronously via {@link AuditLogger}.</p>
 */
public final class AuditEntry {

    private final long   id;
    private final String maceUid;
    private final long   timestamp;
    private final String eventType;
    private final String detail;
    private final String playerUuid;
    private final String playerName;
    private final String world;
    private final int    x;
    private final int    y;
    private final int    z;

    /**
     * Full constructor matching the {@code mace_audit_log} schema.
     *
     * @param id         auto-generated row ID from SQLite (0 for new entries before persistence)
     * @param maceUid    UID of the mace this event concerns, e.g. {@code "MC-0001"}
     * @param timestamp  Unix epoch in seconds when the event occurred
     * @param eventType  short descriptive type string, e.g. {@code "CRAFTED"}, {@code "PICKED_UP"}
     * @param detail     optional free-text detail string (may be {@code null})
     * @param playerUuid UUID of the involved player as a string (may be {@code null})
     * @param playerName display name of the involved player (may be {@code null})
     * @param world      world name where the event occurred (may be {@code null})
     * @param x          block X coordinate of the event location
     * @param y          block Y coordinate of the event location
     * @param z          block Z coordinate of the event location
     */
    public AuditEntry(long id, String maceUid, long timestamp, String eventType,
                      String detail, String playerUuid, String playerName,
                      String world, int x, int y, int z) {
        this.id         = id;
        this.maceUid    = maceUid;
        this.timestamp  = timestamp;
        this.eventType  = eventType;
        this.detail     = detail;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.world      = world;
        this.x          = x;
        this.y          = y;
        this.z          = z;
    }

    /**
     * Convenience constructor for events without a player or location context.
     *
     * @param maceUid   UID of the mace this event concerns
     * @param timestamp Unix epoch in seconds
     * @param eventType short descriptive type string
     * @param detail    optional free-text detail string (may be {@code null})
     */
    public AuditEntry(String maceUid, long timestamp, String eventType, String detail) {
        this(0L, maceUid, timestamp, eventType, detail, null, null, null, 0, 0, 0);
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the database-assigned row ID. Will be {@code 0} for entries that
     * have not yet been persisted.
     */
    public long getId() {
        return id;
    }

    /** Returns the UID of the mace this audit entry belongs to. */
    public String getMaceUid() {
        return maceUid;
    }

    /** Returns the Unix epoch timestamp (seconds) when this event was recorded. */
    public long getTimestamp() {
        return timestamp;
    }

    /** Returns the event type string (e.g. {@code "CRAFTED"}, {@code "DESTROYED"}). */
    public String getEventType() {
        return eventType;
    }

    /** Returns the optional detail string, or {@code null} if none was provided. */
    public String getDetail() {
        return detail;
    }

    /**
     * Returns the UUID of the involved player as a string, or {@code null} if
     * no player was associated with this event.
     */
    public String getPlayerUuid() {
        return playerUuid;
    }

    /**
     * Returns the involved player's display name, or {@code null} if no player
     * was associated with this event.
     */
    public String getPlayerName() {
        return playerName;
    }

    /** Returns the world name, or {@code null} if no location context exists. */
    public String getWorld() {
        return world;
    }

    /** Returns the block X coordinate of the event location. */
    public int getX() {
        return x;
    }

    /** Returns the block Y coordinate of the event location. */
    public int getY() {
        return y;
    }

    /** Returns the block Z coordinate of the event location. */
    public int getZ() {
        return z;
    }

    /**
     * Parses {@link #getPlayerUuid()} into a {@link UUID}, or returns {@code null}
     * if the stored value is null or malformed.
     */
    public UUID getPlayerUuidParsed() {
        if (playerUuid == null) return null;
        try {
            return UUID.fromString(playerUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "AuditEntry{id=" + id
                + ", maceUid='" + maceUid + '\''
                + ", timestamp=" + timestamp
                + ", eventType='" + eventType + '\''
                + ", detail='" + detail + '\''
                + ", playerName='" + playerName + '\''
                + ", world='" + world + '\''
                + ", x=" + x + ", y=" + y + ", z=" + z
                + '}';
    }
}
