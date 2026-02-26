package com.macecontrol.data;

import java.util.UUID;

/**
 * Mutable data object representing a single row in the {@code maces} database table.
 *
 * <p>A {@link MaceEntry} is the authoritative in-memory representation of a tracked mace.
 * Instances are created at craft/give time, loaded from the database at startup, and kept
 * in the {@link MaceRegistry}'s {@link java.util.concurrent.ConcurrentHashMap} cache for
 * fast, thread-safe reads on the main thread.</p>
 *
 * <p>All mutating operations on a live entry must go through {@link MaceRegistry} so the
 * database is updated asynchronously and the cache remains coherent.</p>
 */
public final class MaceEntry {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /** Unique mace identifier, e.g. {@code "MC-0001"}. Never null. */
    private String uid;

    /** Enchantment tier of this mace. Never null. */
    private MaceTier tier;

    /** Lifecycle status of this mace. Never null. */
    private MaceStatus status;

    // ------------------------------------------------------------------
    // Creation metadata
    // ------------------------------------------------------------------

    /** Unix epoch (seconds) when this mace was created. */
    private long createdAt;

    /** UUID of the player who triggered creation, or {@code null} for server/command sources. */
    private UUID createdByUuid;

    /** Display name of the player who triggered creation, or {@code null}. */
    private String createdByName;

    // ------------------------------------------------------------------
    // Location data — updated in real time by event listeners
    // ------------------------------------------------------------------

    /** Broad category of the current location. */
    private MaceLocationType locationType;

    /** Name of the world the mace is currently in, or {@code null} if unknown. */
    private String locationWorld;

    /** Block X coordinate of the mace's current location. */
    private int locationX;

    /** Block Y coordinate of the mace's current location. */
    private int locationY;

    /** Block Z coordinate of the mace's current location. */
    private int locationZ;

    /**
     * UUID of the player currently holding/owning the mace, or {@code null} if not in
     * a player's possession.
     */
    private UUID locationHolderUuid;

    /** Display name of the holder, or {@code null}. */
    private String locationHolderName;

    /**
     * Human-readable type of the container block (e.g. {@code "CHEST"}, {@code "BARREL"}),
     * or {@code null} if the mace is not in a container.
     */
    private String locationContainerType;

    /** Unix epoch (seconds) when the location was last updated. */
    private long locationUpdatedAt;

    // ------------------------------------------------------------------
    // Scan state
    // ------------------------------------------------------------------

    /**
     * Number of consecutive periodic scans where the mace's expected location
     * was accessible but the mace was not found there.
     */
    private int missingScanCount;

    /** Unix epoch (seconds) of the last scan cycle that confirmed this mace's location. */
    private long lastVerifiedAt;

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    /**
     * No-arg constructor for builder-style construction.
     */
    public MaceEntry() {
        this.status   = MaceStatus.ACTIVE;
        this.locationType = MaceLocationType.UNKNOWN;
    }

    /**
     * Minimal constructor for creating a new mace at craft/give time.
     *
     * @param uid           unique mace identifier (never null)
     * @param tier          enchantment tier (never null)
     * @param createdAt     Unix epoch seconds
     * @param createdByUuid UUID of the creating player, or null
     * @param createdByName display name of the creating player, or null
     */
    public MaceEntry(String uid, MaceTier tier, long createdAt,
                     UUID createdByUuid, String createdByName) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("uid must not be null or blank");
        if (tier == null) throw new IllegalArgumentException("tier must not be null");
        this.uid            = uid;
        this.tier           = tier;
        this.status         = MaceStatus.ACTIVE;
        this.createdAt      = createdAt;
        this.createdByUuid  = createdByUuid;
        this.createdByName  = createdByName;
        this.locationType   = MaceLocationType.UNKNOWN;
        this.missingScanCount = 0;
    }

    /**
     * Full constructor for loading an entry from the database.
     *
     * @param uid                  mace UID
     * @param tier                 enchantment tier
     * @param status               lifecycle status
     * @param createdAt            creation epoch seconds
     * @param createdByUuid        creating player UUID, or null
     * @param createdByName        creating player name, or null
     * @param locationType         location type enum
     * @param locationWorld        world name, or null
     * @param locationX            block X
     * @param locationY            block Y
     * @param locationZ            block Z
     * @param locationHolderUuid   holder UUID, or null
     * @param locationHolderName   holder name, or null
     * @param locationContainerType container block type, or null
     * @param locationUpdatedAt    epoch seconds of last location update
     * @param missingScanCount     consecutive missing scan count
     * @param lastVerifiedAt       epoch seconds of last verification
     */
    public MaceEntry(String uid, MaceTier tier, MaceStatus status,
                     long createdAt, UUID createdByUuid, String createdByName,
                     MaceLocationType locationType, String locationWorld,
                     int locationX, int locationY, int locationZ,
                     UUID locationHolderUuid, String locationHolderName,
                     String locationContainerType, long locationUpdatedAt,
                     int missingScanCount, long lastVerifiedAt) {
        this.uid                  = uid;
        this.tier                 = tier;
        this.status               = status;
        this.createdAt            = createdAt;
        this.createdByUuid        = createdByUuid;
        this.createdByName        = createdByName;
        this.locationType         = locationType != null ? locationType : MaceLocationType.UNKNOWN;
        this.locationWorld        = locationWorld;
        this.locationX            = locationX;
        this.locationY            = locationY;
        this.locationZ            = locationZ;
        this.locationHolderUuid   = locationHolderUuid;
        this.locationHolderName   = locationHolderName;
        this.locationContainerType = locationContainerType;
        this.locationUpdatedAt    = locationUpdatedAt;
        this.missingScanCount     = missingScanCount;
        this.lastVerifiedAt       = lastVerifiedAt;
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    /** Returns the unique mace identifier (e.g. {@code "MC-0001"}). */
    public String getUid() { return uid; }

    /** Returns the enchantment tier of this mace. */
    public MaceTier getTier() { return tier; }

    /** Returns the current lifecycle status. */
    public MaceStatus getStatus() { return status; }

    /** Returns the Unix epoch (seconds) when this mace was created. */
    public long getCreatedAt() { return createdAt; }

    /** Returns the UUID of the player who created this mace, or {@code null}. */
    public UUID getCreatedByUuid() { return createdByUuid; }

    /** Returns the name of the player who created this mace, or {@code null}. */
    public String getCreatedByName() { return createdByName; }

    /** Returns the broad location category of this mace. */
    public MaceLocationType getLocationType() { return locationType; }

    /** Returns the world name of the mace's current location, or {@code null}. */
    public String getLocationWorld() { return locationWorld; }

    /** Returns the block X coordinate of the mace's location. */
    public int getLocationX() { return locationX; }

    /** Returns the block Y coordinate of the mace's location. */
    public int getLocationY() { return locationY; }

    /** Returns the block Z coordinate of the mace's location. */
    public int getLocationZ() { return locationZ; }

    /** Returns the UUID of the player currently holding this mace, or {@code null}. */
    public UUID getLocationHolderUuid() { return locationHolderUuid; }

    /** Returns the name of the player currently holding this mace, or {@code null}. */
    public String getLocationHolderName() { return locationHolderName; }

    /** Returns the container block type string, or {@code null} if not in a container. */
    public String getLocationContainerType() { return locationContainerType; }

    /** Returns the Unix epoch (seconds) when the location was last updated. */
    public long getLocationUpdatedAt() { return locationUpdatedAt; }

    /**
     * Returns the number of consecutive periodic scans where the expected location
     * was accessible but the mace was not found.
     */
    public int getMissingScanCount() { return missingScanCount; }

    /** Returns the Unix epoch (seconds) when this mace's location was last verified by a scan. */
    public long getLastVerifiedAt() { return lastVerifiedAt; }

    // ------------------------------------------------------------------
    // Setters — package-private to enforce mutation via MaceRegistry
    // ------------------------------------------------------------------

    /** Sets the UID. Should only be called during construction or loading. */
    public void setUid(String uid) { this.uid = uid; }

    /** Updates the enchantment tier. */
    public void setTier(MaceTier tier) {
        if (tier == null) throw new IllegalArgumentException("tier must not be null");
        this.tier = tier;
    }

    /** Updates the lifecycle status. */
    public void setStatus(MaceStatus status) {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        this.status = status;
    }

    /** Sets the creation epoch timestamp. */
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    /** Sets the creating player UUID. */
    public void setCreatedByUuid(UUID createdByUuid) { this.createdByUuid = createdByUuid; }

    /** Sets the creating player name. */
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    /** Sets the location type. */
    public void setLocationType(MaceLocationType locationType) {
        this.locationType = locationType != null ? locationType : MaceLocationType.UNKNOWN;
    }

    /** Sets the location world name. */
    public void setLocationWorld(String locationWorld) { this.locationWorld = locationWorld; }

    /** Sets the block X coordinate. */
    public void setLocationX(int locationX) { this.locationX = locationX; }

    /** Sets the block Y coordinate. */
    public void setLocationY(int locationY) { this.locationY = locationY; }

    /** Sets the block Z coordinate. */
    public void setLocationZ(int locationZ) { this.locationZ = locationZ; }

    /** Sets the holder UUID. */
    public void setLocationHolderUuid(UUID locationHolderUuid) { this.locationHolderUuid = locationHolderUuid; }

    /** Sets the holder name. */
    public void setLocationHolderName(String locationHolderName) { this.locationHolderName = locationHolderName; }

    /** Sets the container block type string. */
    public void setLocationContainerType(String locationContainerType) { this.locationContainerType = locationContainerType; }

    /** Sets the epoch timestamp of the last location update. */
    public void setLocationUpdatedAt(long locationUpdatedAt) { this.locationUpdatedAt = locationUpdatedAt; }

    /** Sets the consecutive missing scan count. */
    public void setMissingScanCount(int missingScanCount) { this.missingScanCount = missingScanCount; }

    /** Sets the epoch timestamp of the last verification. */
    public void setLastVerifiedAt(long lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if this mace is currently in an active, non-destroyed state.
     */
    public boolean isActive() {
        return status == MaceStatus.ACTIVE;
    }

    /**
     * Returns a concise human-readable summary for logging and admin commands.
     */
    @Override
    public String toString() {
        return "MaceEntry{uid='" + uid + '\''
                + ", tier=" + tier
                + ", status=" + status
                + ", locationType=" + locationType
                + ", world='" + locationWorld + '\''
                + ", xyz=(" + locationX + "," + locationY + "," + locationZ + ")"
                + ", holder='" + locationHolderName + '\''
                + ", missingScanCount=" + missingScanCount
                + '}';
    }
}
