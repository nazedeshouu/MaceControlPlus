package com.macecontrol.data;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the SQLite database connection and all persistence operations for MaceControl.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>All write operations are submitted to a dedicated single-threaded executor
 *       ({@link #writeExecutor}) so they never block the Bukkit main thread.</li>
 *   <li>All read operations at startup ({@link #loadAllEntries()},
 *       {@link #loadAuditHistory}) are synchronous and must only be called from the
 *       main thread <em>before</em> the server accepts player connections.</li>
 *   <li>The in-memory cache in {@link MaceRegistry} is the authoritative read source
 *       at runtime; the database is only queried for history/audit retrieval.</li>
 * </ul>
 *
 * <h3>Connection</h3>
 * A single persistent {@link Connection} is kept open for the lifetime of the plugin.
 * WAL mode is enabled to allow concurrent reads without blocking writes.
 */
public final class DatabaseManager {

    /** Single-threaded executor for all async DB writes. */
    private final ExecutorService writeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MaceControl-DB-Writer");
                t.setDaemon(true);
                return t;
            });

    private Connection connection;
    private Logger     logger;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Opens the SQLite database, enables WAL mode, and creates all required tables
     * and indexes if they do not already exist.
     *
     * <p>Must be called synchronously from {@code onEnable()} before any other method.</p>
     *
     * @param plugin the owning plugin instance, used to resolve the data folder and logger
     */
    public void initialize(Plugin plugin) {
        this.logger = plugin.getLogger();
        File dbFile = new File(plugin.getDataFolder(), "macecontrol.db");
        plugin.getDataFolder().mkdirs();

        try {
            // Load the relocated SQLite driver class so the DriverManager finds it.
            Class.forName("com.macecontrol.libs.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // Enable Write-Ahead Logging for better concurrency.
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA synchronous=NORMAL;");
                st.execute("PRAGMA foreign_keys=ON;");
            }

            createTables();
            logger.info("Database initialised at: " + dbFile.getAbsolutePath());
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "SQLite JDBC driver not found — ensure the shaded JAR is correct.", e);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to initialise SQLite database.", e);
        }
    }

    /**
     * Creates the {@code maces} and {@code mace_audit_log} tables and their indexes
     * if they do not already exist.
     */
    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS maces (
                        uid                    TEXT    PRIMARY KEY,
                        tier                   TEXT    NOT NULL,
                        status                 TEXT    NOT NULL DEFAULT 'ACTIVE',
                        created_at             INTEGER NOT NULL,
                        created_by_uuid        TEXT,
                        created_by_name        TEXT,
                        location_type          TEXT,
                        location_world         TEXT,
                        location_x             INTEGER,
                        location_y             INTEGER,
                        location_z             INTEGER,
                        location_holder_uuid   TEXT,
                        location_holder_name   TEXT,
                        location_container_type TEXT,
                        location_updated_at    INTEGER,
                        missing_scan_count     INTEGER DEFAULT 0,
                        last_verified_at       INTEGER
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS mace_audit_log (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        mace_uid    TEXT    NOT NULL,
                        timestamp   INTEGER NOT NULL,
                        event_type  TEXT    NOT NULL,
                        detail      TEXT,
                        player_uuid TEXT,
                        player_name TEXT,
                        world       TEXT,
                        x           INTEGER,
                        y           INTEGER,
                        z           INTEGER,
                        FOREIGN KEY (mace_uid) REFERENCES maces(uid)
                    );
                    """);

            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_uid  ON mace_audit_log(mace_uid);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON mace_audit_log(timestamp);");
        }
    }

    /**
     * Closes the database connection and shuts down the write executor, waiting up to
     * 10 seconds for in-flight writes to complete.
     *
     * <p>Must be called from {@code onDisable()} on the main thread.</p>
     */
    public void close() {
        writeExecutor.shutdown();
        try {
            if (!writeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("DB write executor did not terminate in 10 s — some writes may be lost.");
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeExecutor.shutdownNow();
        }

        if (connection != null) {
            try {
                connection.close();
                logger.info("Database connection closed.");
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error closing database connection.", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mace CRUD — async writes
    // -------------------------------------------------------------------------

    /**
     * Asynchronously inserts or replaces a {@link MaceEntry} in the database
     * (full upsert).
     *
     * @param entry the entry to persist; must have a non-null UID
     */
    public void saveEntry(MaceEntry entry) {
        writeExecutor.submit(() -> {
            String sql = """
                    INSERT OR REPLACE INTO maces (
                        uid, tier, status, created_at, created_by_uuid, created_by_name,
                        location_type, location_world, location_x, location_y, location_z,
                        location_holder_uuid, location_holder_name, location_container_type,
                        location_updated_at, missing_scan_count, last_verified_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindMaceEntry(ps, entry);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to save MaceEntry uid=" + entry.getUid(), e);
            }
        });
    }

    /**
     * Asynchronously updates all mutable fields of an existing {@link MaceEntry}
     * in the database. Equivalent to {@link #saveEntry(MaceEntry)} — both perform
     * a full upsert. Provided as a semantically clearer alias.
     *
     * @param entry the entry whose values should be written to the database
     */
    public void updateEntry(MaceEntry entry) {
        saveEntry(entry);
    }

    /**
     * Binds all fields of a {@link MaceEntry} to the prepared statement produced by
     * {@link #saveEntry(MaceEntry)}.
     */
    private void bindMaceEntry(PreparedStatement ps, MaceEntry e) throws SQLException {
        ps.setString(1, e.getUid());
        ps.setString(2, e.getTier().name());
        ps.setString(3, e.getStatus().name());
        ps.setLong(4, e.getCreatedAt());
        ps.setString(5, e.getCreatedByUuid() != null ? e.getCreatedByUuid().toString() : null);
        ps.setString(6, e.getCreatedByName());
        ps.setString(7, e.getLocationType() != null ? e.getLocationType().name() : MaceLocationType.UNKNOWN.name());
        ps.setString(8, e.getLocationWorld());
        ps.setInt(9, e.getLocationX());
        ps.setInt(10, e.getLocationY());
        ps.setInt(11, e.getLocationZ());
        ps.setString(12, e.getLocationHolderUuid() != null ? e.getLocationHolderUuid().toString() : null);
        ps.setString(13, e.getLocationHolderName());
        ps.setString(14, e.getLocationContainerType());
        ps.setLong(15, e.getLocationUpdatedAt());
        ps.setInt(16, e.getMissingScanCount());
        ps.setLong(17, e.getLastVerifiedAt());
    }

    // -------------------------------------------------------------------------
    // Startup read — synchronous
    // -------------------------------------------------------------------------

    /**
     * Synchronously loads all {@link MaceEntry} rows from the database.
     *
     * <p>This method is only called once at plugin startup, before any player connections
     * are accepted. It must not be called from async contexts.</p>
     *
     * @return a list of all persisted mace entries (never null, may be empty)
     */
    public List<MaceEntry> loadAllEntries() {
        List<MaceEntry> entries = new ArrayList<>();
        if (connection == null) return entries;

        String sql = "SELECT * FROM maces;";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                entries.add(rowToMaceEntry(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load mace entries from database.", e);
        }
        return entries;
    }

    /**
     * Converts a {@link ResultSet} row into a {@link MaceEntry}.
     */
    private MaceEntry rowToMaceEntry(ResultSet rs) throws SQLException {
        String uid              = rs.getString("uid");
        MaceTier tier           = MaceTier.fromString(rs.getString("tier"));
        MaceStatus status       = MaceStatus.fromString(rs.getString("status"));
        long createdAt          = rs.getLong("created_at");
        String createdByUuidStr = rs.getString("created_by_uuid");
        String createdByName    = rs.getString("created_by_name");
        MaceLocationType locType = MaceLocationType.fromString(rs.getString("location_type"));
        String locWorld         = rs.getString("location_world");
        int locX                = rs.getInt("location_x");
        int locY                = rs.getInt("location_y");
        int locZ                = rs.getInt("location_z");
        String holderUuidStr    = rs.getString("location_holder_uuid");
        String holderName       = rs.getString("location_holder_name");
        String containerType    = rs.getString("location_container_type");
        long locUpdatedAt       = rs.getLong("location_updated_at");
        int missingScanCount    = rs.getInt("missing_scan_count");
        long lastVerifiedAt     = rs.getLong("last_verified_at");

        UUID createdByUuid = parseUuid(createdByUuidStr);
        UUID holderUuid    = parseUuid(holderUuidStr);

        if (tier == null) tier = MaceTier.LIMITED;
        if (status == null) status = MaceStatus.ACTIVE;

        return new MaceEntry(
                uid, tier, status, createdAt, createdByUuid, createdByName,
                locType, locWorld, locX, locY, locZ,
                holderUuid, holderName, containerType, locUpdatedAt,
                missingScanCount, lastVerifiedAt
        );
    }

    // -------------------------------------------------------------------------
    // Audit log — async write, sync read
    // -------------------------------------------------------------------------

    /**
     * Asynchronously inserts an {@link AuditEntry} into the {@code mace_audit_log} table.
     *
     * @param entry the audit entry to persist
     */
    public void logAudit(AuditEntry entry) {
        writeExecutor.submit(() -> {
            String sql = """
                    INSERT INTO mace_audit_log
                        (mace_uid, timestamp, event_type, detail, player_uuid, player_name, world, x, y, z)
                    VALUES (?,?,?,?,?,?,?,?,?,?);
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, entry.getMaceUid());
                ps.setLong(2, entry.getTimestamp());
                ps.setString(3, entry.getEventType());
                ps.setString(4, entry.getDetail());
                ps.setString(5, entry.getPlayerUuid());
                ps.setString(6, entry.getPlayerName());
                ps.setString(7, entry.getWorld());
                ps.setInt(8, entry.getX());
                ps.setInt(9, entry.getY());
                ps.setInt(10, entry.getZ());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to insert audit entry for mace " + entry.getMaceUid(), e);
            }
        });
    }

    /**
     * Synchronously retrieves the most recent audit entries for a specific mace,
     * ordered newest-first.
     *
     * @param maceUid the UID of the mace whose history is requested
     * @param limit   maximum number of entries to return
     * @return a list of audit entries (newest first), never null
     */
    public List<AuditEntry> loadAuditHistory(String maceUid, int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        if (connection == null) return entries;

        String sql = """
                SELECT * FROM mace_audit_log
                WHERE mace_uid = ?
                ORDER BY timestamp DESC
                LIMIT ?;
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, maceUid);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(rowToAuditEntry(rs));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load audit history for mace " + maceUid, e);
        }
        return entries;
    }

    /**
     * Asynchronously prunes audit entries for a single mace so that at most
     * {@code maxEntries} rows are retained (keeping the most recent).
     *
     * @param maceUid    the mace UID to prune
     * @param maxEntries maximum entries to keep
     */
    public void pruneAuditHistory(String maceUid, int maxEntries) {
        writeExecutor.submit(() -> {
            String sql = """
                    DELETE FROM mace_audit_log
                    WHERE mace_uid = ?
                      AND id NOT IN (
                          SELECT id FROM mace_audit_log
                          WHERE mace_uid = ?
                          ORDER BY timestamp DESC
                          LIMIT ?
                      );
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, maceUid);
                ps.setString(2, maceUid);
                ps.setInt(3, maxEntries);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to prune audit history for mace " + maceUid, e);
            }
        });
    }

    /**
     * Converts a {@link ResultSet} row from {@code mace_audit_log} to an {@link AuditEntry}.
     */
    private AuditEntry rowToAuditEntry(ResultSet rs) throws SQLException {
        return new AuditEntry(
                rs.getLong("id"),
                rs.getString("mace_uid"),
                rs.getLong("timestamp"),
                rs.getString("event_type"),
                rs.getString("detail"),
                rs.getString("player_uuid"),
                rs.getString("player_name"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z")
        );
    }

    // -------------------------------------------------------------------------
    // UID counter — stored as a special metadata row in the maces table and also
    // in internal-data.yml. The registry keeps the authoritative in-memory value.
    // -------------------------------------------------------------------------

    /**
     * Synchronously reads the current UID counter from the
     * {@code uid_counter} metadata row, or returns 1 if not found.
     *
     * <p>The counter is stored in a dedicated metadata table to avoid coupling it
     * to the maces table. If the metadata table does not yet exist, it is created.</p>
     *
     * @return the next UID integer to assign (1-based)
     */
    public int getNextUidCounter() {
        if (connection == null) return 1;
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS macecontrol_meta (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    );
                    """);
            try (ResultSet rs = st.executeQuery(
                    "SELECT value FROM macecontrol_meta WHERE key='uid_counter';")) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString("value"));
                }
            }
        } catch (SQLException | NumberFormatException e) {
            logger.log(Level.WARNING, "Could not read UID counter from DB; defaulting to 1.", e);
        }
        return 1;
    }

    /**
     * Asynchronously persists the UID counter value to the {@code macecontrol_meta} table.
     *
     * @param value the new counter value to persist
     */
    public void setNextUidCounter(int value) {
        writeExecutor.submit(() -> {
            String sql = """
                    INSERT OR REPLACE INTO macecontrol_meta (key, value)
                    VALUES ('uid_counter', ?);
                    """;
            // Ensure the meta table exists first (may be a fresh DB).
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS macecontrol_meta (
                            key   TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        );
                        """);
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to ensure macecontrol_meta table.", e);
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, String.valueOf(value));
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to persist UID counter value " + value, e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a UUID string safely, returning {@code null} if the input is null or malformed.
     */
    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
