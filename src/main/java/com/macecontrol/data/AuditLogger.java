package com.macecontrol.data;

import com.macecontrol.config.MaceConfig;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Convenience wrapper around {@link DatabaseManager} for recording mace audit events.
 *
 * <p>Every call to a {@code log(...)} method:</p>
 * <ol>
 *   <li>Constructs an {@link AuditEntry} with the current epoch second as the timestamp.</li>
 *   <li>Submits the entry to {@link DatabaseManager#logAudit(AuditEntry)} for async persistence.</li>
 *   <li>If {@code audit.log-to-file} is {@code true} in config, also appends a human-readable
 *       line to {@code plugins/MaceControl/mace-audit.log}.</li>
 *   <li>Schedules an async prune of entries beyond {@code audit.max-history-per-mace}.</li>
 * </ol>
 *
 * <p>All operations that touch the filesystem or database are either already async (via the
 * {@link DatabaseManager}'s write executor) or delegated to a Bukkit async task.</p>
 */
public final class AuditLogger {

    private static final DateTimeFormatter LOG_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DatabaseManager db;
    private final MaceConfig      config;
    private final Plugin          plugin;
    private final Logger          logger;

    /** Flat-file log destination. May be null if file logging is disabled in config. */
    private File logFile;

    /**
     * Constructs an {@link AuditLogger}.
     *
     * @param db     the database manager used for persistence
     * @param config the plugin configuration (consulted for file-logging toggle and max history)
     * @param plugin the owning plugin instance (used for data folder resolution and async tasks)
     */
    public AuditLogger(DatabaseManager db, MaceConfig config, Plugin plugin) {
        this.db     = db;
        this.config = config;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        initLogFile();
    }

    /**
     * Resolves (and if necessary creates) the flat-file log at
     * {@code plugins/MaceControl/mace-audit.log}.
     */
    private void initLogFile() {
        if (config.isLogToFile()) {
            logFile = new File(plugin.getDataFolder(), "mace-audit.log");
            try {
                plugin.getDataFolder().mkdirs();
                if (!logFile.exists()) {
                    logFile.createNewFile();
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not create mace-audit.log — file logging disabled.", e);
                logFile = null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public logging API
    // -------------------------------------------------------------------------

    /**
     * Records an audit event with full context (player and location).
     *
     * @param maceUid    the UID of the mace involved (e.g. {@code "MC-0001"})
     * @param eventType  short event type string (e.g. {@code "PICKED_UP"}, {@code "DESTROYED"})
     * @param detail     optional free-text detail; may be {@code null}
     * @param playerUuid UUID of the involved player; may be {@code null}
     * @param playerName display name of the involved player; may be {@code null}
     * @param location   Bukkit location of the event; may be {@code null}
     */
    public void log(String maceUid, String eventType, String detail,
                    UUID playerUuid, String playerName, Location location) {
        long now    = Instant.now().getEpochSecond();
        String world = null;
        int x = 0, y = 0, z = 0;
        if (location != null && location.getWorld() != null) {
            world = location.getWorld().getName();
            x     = location.getBlockX();
            y     = location.getBlockY();
            z     = location.getBlockZ();
        }

        AuditEntry entry = new AuditEntry(
                0L, maceUid, now, eventType, detail,
                playerUuid != null ? playerUuid.toString() : null,
                playerName, world, x, y, z
        );

        persistEntry(entry);
    }

    /**
     * Records an audit event without player or location context.
     *
     * @param maceUid   the UID of the mace involved
     * @param eventType short event type string
     * @param detail    optional free-text detail; may be {@code null}
     */
    public void log(String maceUid, String eventType, String detail) {
        long now = Instant.now().getEpochSecond();
        AuditEntry entry = new AuditEntry(0L, maceUid, now, eventType, detail, null, null, null, 0, 0, 0);
        persistEntry(entry);
    }

    /**
     * Retrieves the most recent audit entries for a mace (newest first).
     *
     * <p>This is a synchronous call and should only be made in response to admin commands —
     * never from hot code paths.</p>
     *
     * @param maceUid the UID to look up
     * @param limit   maximum number of entries to return
     * @return list of audit entries, newest first; never null
     */
    public List<AuditEntry> getHistory(String maceUid, int limit) {
        return db.loadAuditHistory(maceUid, limit);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Sends the entry to the database and optionally appends to the flat-file log.
     * Then schedules a prune of excess entries.
     */
    private void persistEntry(AuditEntry entry) {
        // 1. DB write (async via DatabaseManager's executor)
        db.logAudit(entry);

        // 2. Flat-file log (appended in a Bukkit async task to keep I/O off main thread)
        if (logFile != null && config.isLogToFile()) {
            final File target = logFile;
            final String line = formatLogLine(entry);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try (PrintWriter pw = new PrintWriter(new FileWriter(target, true))) {
                    pw.println(line);
                } catch (IOException e) {
                    // Log at WARNING to avoid spam; the DB is the authoritative store.
                    logger.log(Level.WARNING, "Failed to write audit line to mace-audit.log", e);
                }
            });
        }

        // 3. Async prune to keep history bounded.
        final int maxHistory = config.getMaxHistoryPerMace();
        if (maxHistory > 0) {
            db.pruneAuditHistory(entry.getMaceUid(), maxHistory);
        }
    }

    /**
     * Formats an {@link AuditEntry} into a single flat-file log line.
     *
     * <p>Format: {@code [yyyy-MM-dd HH:mm:ss UTC] [UID] [EVENT_TYPE] player@world(x,y,z) detail}</p>
     */
    private String formatLogLine(AuditEntry entry) {
        String timestamp = LOG_DATE_FMT.format(Instant.ofEpochSecond(entry.getTimestamp()));
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(timestamp).append(" UTC] ");
        sb.append('[').append(entry.getMaceUid()).append("] ");
        sb.append('[').append(entry.getEventType()).append("] ");

        if (entry.getPlayerName() != null) {
            sb.append(entry.getPlayerName());
        } else {
            sb.append("(server)");
        }

        if (entry.getWorld() != null) {
            sb.append('@').append(entry.getWorld())
              .append('(').append(entry.getX())
              .append(',').append(entry.getY())
              .append(',').append(entry.getZ()).append(')');
        }

        if (entry.getDetail() != null) {
            sb.append(" | ").append(entry.getDetail());
        }

        return sb.toString();
    }
}
