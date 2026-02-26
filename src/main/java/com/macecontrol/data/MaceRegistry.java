package com.macecontrol.data;

import com.macecontrol.config.MaceConfig;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Central in-memory cache and database gateway for all tracked maces.
 *
 * <h3>Design contract</h3>
 * <ul>
 *   <li>All reads at runtime go to the {@link ConcurrentHashMap} cache — no database I/O.</li>
 *   <li>All writes update the cache synchronously on the calling thread, then persist to the
 *       database asynchronously via {@link DatabaseManager}.</li>
 *   <li>The registry is the single source of truth at runtime; the database is the durable
 *       backing store consulted only at startup.</li>
 * </ul>
 *
 * <h3>Per-tier slot caps</h3>
 * The registry exposes per-tier active counts ({@link #countActiveByTier(MaceTier)}) so that
 * callers can enforce the invariants:
 * <pre>
 *   countActive(LIMITED) &lt;= maxNormalMaceLimit
 *   countActive(FULL)    &lt;= maxFullMaceLimit
 * </pre>
 *
 * <h3>Thread safety</h3>
 * The cache is a {@link ConcurrentHashMap}; individual field mutations on {@link MaceEntry}
 * are not independently synchronized. Callers must not read an entry mid-update on a different
 * thread. All mutation methods in this class are designed to be called from the Bukkit main
 * thread only; the DB flush is delegated to the async executor in {@link DatabaseManager}.
 */
public final class MaceRegistry {

    /** In-memory map of UID → MaceEntry. The authoritative runtime store. */
    private final ConcurrentHashMap<String, MaceEntry> cache = new ConcurrentHashMap<>();

    private final DatabaseManager db;
    private final AuditLogger     auditLogger;
    private final MaceConfig      config;

    /**
     * In-memory UID counter. Monotonically increasing. Persisted to internal-data.yml and DB.
     * Access is guarded by {@link #generateNextUid()}'s {@code synchronized} modifier.
     */
    private int nextUidCounter = 1;

    /**
     * Queued revocations for offline players. Maps player UUID → list of mace UIDs to revoke
     * when that player next logs in.
     */
    private final Map<UUID, List<String>> pendingRevocations = new ConcurrentHashMap<>();

    private Logger logger;

    /**
     * Constructs a {@link MaceRegistry}.
     *
     * @param db          the database manager for async persistence
     * @param auditLogger the audit logger for recording registry changes
     * @param config      the plugin configuration for limit lookups
     */
    public MaceRegistry(DatabaseManager db, AuditLogger auditLogger, MaceConfig config) {
        this.db          = db;
        this.auditLogger = auditLogger;
        this.config      = config;
    }

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

    /**
     * Loads all persisted {@link MaceEntry} records from the database into the in-memory cache.
     *
     * <p>Must be called once from {@code onEnable()} after {@link DatabaseManager#initialize(org.bukkit.plugin.Plugin)}
     * and before the server accepts player connections. The logger is retrieved from the first
     * available entry's class or from the owning plugin later — pass it explicitly by calling
     * {@link #setLogger(Logger)} if needed.</p>
     */
    public void loadFromDatabase() {
        List<MaceEntry> entries = db.loadAllEntries();
        for (MaceEntry entry : entries) {
            cache.put(entry.getUid(), entry);
        }
        // Derive the next UID counter from the DB rather than relying solely on internal-data.yml.
        int dbCounter = db.getNextUidCounter();
        // Also derive from existing UIDs as a safety check.
        int derivedMax = deriveMaxUidFromCache();
        nextUidCounter = Math.max(dbCounter, derivedMax + 1);

        if (logger != null) {
            logger.info("Loaded " + entries.size() + " mace entries from database. Next UID counter: " + nextUidCounter);
        }
    }

    /**
     * Sets the logger used for informational messages emitted by the registry.
     *
     * @param logger the logger to use
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Scans the cache for the highest numeric UID and returns that number so the counter
     * can be seeded above any existing entries.
     */
    private int deriveMaxUidFromCache() {
        int max = 0;
        for (String uid : cache.keySet()) {
            // Expected format: "MC-NNNN"
            if (uid != null && uid.startsWith("MC-")) {
                try {
                    int n = Integer.parseInt(uid.substring(3));
                    if (n > max) max = n;
                } catch (NumberFormatException ignored) {
                    // Non-standard UID; skip.
                }
            }
        }
        return max;
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /**
     * Registers a new {@link MaceEntry} in the cache and persists it asynchronously.
     *
     * <p>This is the entry point for all newly created maces (crafted or admin-given).</p>
     *
     * @param entry the fully populated entry to register; its UID must be unique
     * @throws IllegalArgumentException if an entry with the same UID already exists
     */
    public void register(MaceEntry entry) {
        if (entry == null) throw new IllegalArgumentException("entry must not be null");
        if (cache.containsKey(entry.getUid())) {
            throw new IllegalArgumentException("A mace with UID '" + entry.getUid() + "' is already registered.");
        }
        cache.put(entry.getUid(), entry);
        db.saveEntry(entry);
    }

    /**
     * Returns the {@link MaceEntry} for the given UID from the cache, or {@code null}
     * if no such mace is registered.
     *
     * @param uid the mace UID to look up (e.g. {@code "MC-0001"})
     * @return the entry, or {@code null}
     */
    public MaceEntry getEntry(String uid) {
        if (uid == null) return null;
        return cache.get(uid);
    }

    /**
     * Returns an unmodifiable view of all registered {@link MaceEntry} objects,
     * regardless of status.
     *
     * @return collection of all entries; never null
     */
    public Collection<MaceEntry> getAllEntries() {
        return Collections.unmodifiableCollection(cache.values());
    }

    /**
     * Returns a list of all entries whose status is {@link MaceStatus#ACTIVE}.
     *
     * @return list of active entries; never null, may be empty
     */
    public List<MaceEntry> getActiveEntries() {
        return cache.values().stream()
                .filter(e -> e.getStatus() == MaceStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    /**
     * Returns a list of all entries whose status is {@link MaceStatus#ACTIVE} and whose
     * tier matches the given {@link MaceTier}.
     *
     * @param tier the tier to filter by
     * @return list of active entries of the given tier; never null, may be empty
     */
    public List<MaceEntry> getActiveByTier(MaceTier tier) {
        if (tier == null) return Collections.emptyList();
        return cache.values().stream()
                .filter(e -> e.getStatus() == MaceStatus.ACTIVE && e.getTier() == tier)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Counts
    // -------------------------------------------------------------------------

    /**
     * Returns the total number of maces currently in {@link MaceStatus#ACTIVE} state
     * across all tiers.
     *
     * @return count of active maces
     */
    public int countActive() {
        return (int) cache.values().stream()
                .filter(e -> e.getStatus() == MaceStatus.ACTIVE)
                .count();
    }

    /**
     * Returns the number of maces currently in {@link MaceStatus#ACTIVE} state for
     * the specified tier.
     *
     * <p>This is the critical method used to enforce per-tier slot caps before allowing
     * a craft or {@code /mace give}.</p>
     *
     * @param tier the tier to count
     * @return count of active maces for the given tier
     */
    public int countActiveByTier(MaceTier tier) {
        if (tier == null) return 0;
        return (int) cache.values().stream()
                .filter(e -> e.getStatus() == MaceStatus.ACTIVE && e.getTier() == tier)
                .count();
    }

    // -------------------------------------------------------------------------
    // Location updates
    // -------------------------------------------------------------------------

    /**
     * Updates the location of a mace immediately in the cache and asynchronously in the DB.
     *
     * @param uid            the UID of the mace to update
     * @param type           the new location type
     * @param world          world name, or null
     * @param x              block X
     * @param y              block Y
     * @param z              block Z
     * @param holderUuid     UUID of the player holding the mace, or null
     * @param holderName     display name of the holder, or null
     * @param containerType  type string of the container block, or null
     */
    public void updateLocation(String uid, MaceLocationType type, String world,
                               int x, int y, int z,
                               UUID holderUuid, String holderName,
                               String containerType) {
        MaceEntry entry = cache.get(uid);
        if (entry == null) return;

        entry.setLocationType(type);
        entry.setLocationWorld(world);
        entry.setLocationX(x);
        entry.setLocationY(y);
        entry.setLocationZ(z);
        entry.setLocationHolderUuid(holderUuid);
        entry.setLocationHolderName(holderName);
        entry.setLocationContainerType(containerType);
        entry.setLocationUpdatedAt(Instant.now().getEpochSecond());

        db.updateEntry(entry);
    }

    /**
     * Updates the {@link MaceStatus} of a mace immediately in the cache and
     * asynchronously in the DB.
     *
     * @param uid    the mace UID
     * @param status the new status
     */
    public void setStatus(String uid, MaceStatus status) {
        MaceEntry entry = cache.get(uid);
        if (entry == null) return;
        entry.setStatus(status);
        db.updateEntry(entry);
    }

    /**
     * Updates the missing scan count of a mace immediately in the cache and
     * asynchronously in the DB.
     *
     * @param uid   the mace UID
     * @param count the new missing scan count value
     */
    public void setMissingScanCount(String uid, int count) {
        MaceEntry entry = cache.get(uid);
        if (entry == null) return;
        entry.setMissingScanCount(count);
        db.updateEntry(entry);
    }

    /**
     * Updates the last-verified timestamp of a mace immediately in the cache and
     * asynchronously in the DB.
     *
     * @param uid          the mace UID
     * @param epochSeconds Unix epoch seconds of the verification time
     */
    public void setLastVerifiedAt(String uid, long epochSeconds) {
        MaceEntry entry = cache.get(uid);
        if (entry == null) return;
        entry.setLastVerifiedAt(epochSeconds);
        db.updateEntry(entry);
    }

    /**
     * Updates the tier of a mace immediately in the cache and asynchronously in the DB.
     *
     * @param uid  the mace UID
     * @param tier the new tier
     */
    public void setTier(String uid, MaceTier tier) {
        MaceEntry entry = cache.get(uid);
        if (entry == null) return;
        entry.setTier(tier);
        db.updateEntry(entry);
    }

    // -------------------------------------------------------------------------
    // UID generation
    // -------------------------------------------------------------------------

    /**
     * Generates and returns the next unique mace UID in {@code "MC-NNNN"} format,
     * auto-incrementing the internal counter and persisting it asynchronously.
     *
     * <p>This method is {@code synchronized} to guarantee uniqueness even if called
     * from multiple contexts on the main thread during the same tick.</p>
     *
     * @return the new UID string (e.g. {@code "MC-0001"})
     */
    public synchronized String generateNextUid() {
        String uid = String.format("MC-%04d", nextUidCounter);
        nextUidCounter++;
        db.setNextUidCounter(nextUidCounter);
        return uid;
    }

    /**
     * Sets the UID counter (called during startup to seed from internal-data.yml or DB).
     *
     * @param value the counter value to set (must be &gt; 0)
     */
    public synchronized void setNextUidCounter(int value) {
        if (value > 0) {
            nextUidCounter = value;
        }
    }

    /**
     * Returns the current (next-to-use) UID counter value.
     *
     * @return the next UID counter
     */
    public synchronized int getNextUidCounter() {
        return nextUidCounter;
    }

    // -------------------------------------------------------------------------
    // Queued revocations (offline players)
    // -------------------------------------------------------------------------

    /**
     * Queues a mace revocation to be executed when the specified player next logs in.
     *
     * <p>Used when {@code /mace revoke} is issued for a mace held by an offline player.</p>
     *
     * @param playerUuid the UUID of the offline player
     * @param maceUid    the UID of the mace to revoke
     */
    public void queueRevocation(UUID playerUuid, String maceUid) {
        pendingRevocations
                .computeIfAbsent(playerUuid, k -> new ArrayList<>())
                .add(maceUid);
    }

    /**
     * Retrieves and removes all queued mace UID revocations for a player, clearing
     * the queue in the process.
     *
     * <p>Should be called on {@code PlayerJoinEvent} to process pending revocations.</p>
     *
     * @param playerUuid the UUID of the player who just joined
     * @return list of mace UIDs to revoke (may be empty, never null)
     */
    public List<String> popQueuedRevocations(UUID playerUuid) {
        List<String> pending = pendingRevocations.remove(playerUuid);
        return pending != null ? pending : Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Returns the number of entries currently held in the in-memory cache,
     * regardless of status.
     *
     * @return total cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Returns {@code true} if a mace with the given UID exists in the cache.
     *
     * @param uid the UID to check
     * @return whether the UID is known to the registry
     */
    public boolean isKnown(String uid) {
        return uid != null && cache.containsKey(uid);
    }
}
