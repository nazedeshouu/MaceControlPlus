package com.macecontrol;

import com.macecontrol.commands.MaceCommand;
import com.macecontrol.commands.MaceTabCompleter;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.control.CraftController;
import com.macecontrol.control.DestructionWatcher;
import com.macecontrol.control.EnchantGuard;
import com.macecontrol.data.*;
import com.macecontrol.tracking.DupeDetector;
import com.macecontrol.tracking.MaceIdentifier;
import com.macecontrol.tracking.PeriodicScanner;
import com.macecontrol.tracking.RealTimeTracker;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.logging.Level;

/**
 * Main plugin class for MaceControl.
 *
 * <p>Bootstraps all sub-systems in the correct dependency order on enable and
 * cleanly shuts them down on disable. Acts as the service locator for other
 * classes that need cross-cutting references (config, registry, audit logger).</p>
 *
 * <h3>Enable sequence</h3>
 * <ol>
 *   <li>Save default {@code config.yml}</li>
 *   <li>Load / generate {@code internal-data.yml} (HMAC secret, UID counter)</li>
 *   <li>Construct {@link MaceConfig}</li>
 *   <li>Construct {@link DatabaseManager} and initialise the SQLite schema</li>
 *   <li>Construct {@link AuditLogger}</li>
 *   <li>Construct {@link MaceRegistry} and load all entries from the database</li>
 *   <li>Register Bukkit event listeners (Agent 2 classes)</li>
 *   <li>Start the periodic scanner (Agent 2)</li>
 *   <li>Register commands and tab completers (Agent 3 classes)</li>
 * </ol>
 *
 * <p>Agent 2 and Agent 3 classes are referenced by their expected simple names.
 * The compiler will not resolve these until the other agents deliver their files.</p>
 */
public final class MaceControl extends JavaPlugin {

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static MaceControl instance;

    // -------------------------------------------------------------------------
    // Sub-system references
    // -------------------------------------------------------------------------

    private MaceConfig      maceConfig;
    private DatabaseManager databaseManager;
    private AuditLogger     auditLogger;
    private MaceRegistry    registry;

    // internal-data.yml handle
    private FileConfiguration internalData;
    private File              internalDataFile;

    // =========================================================================
    // JavaPlugin lifecycle
    // =========================================================================

    @Override
    public void onEnable() {
        instance = this;

        // ------------------------------------------------------------------
        // 1. Default config
        // ------------------------------------------------------------------
        saveDefaultConfig();

        // ------------------------------------------------------------------
        // 2. Internal data (HMAC secret, UID counter, scan timestamps)
        // ------------------------------------------------------------------
        initInternalData();

        // ------------------------------------------------------------------
        // 3. MaceConfig wrapper
        // ------------------------------------------------------------------
        maceConfig = new MaceConfig(this);

        // ------------------------------------------------------------------
        // 4. Database
        // ------------------------------------------------------------------
        databaseManager = new DatabaseManager();
        databaseManager.initialize(this);

        // ------------------------------------------------------------------
        // 5. Audit logger
        // ------------------------------------------------------------------
        auditLogger = new AuditLogger(databaseManager, maceConfig, this);

        // ------------------------------------------------------------------
        // 6. Registry — load from DB, then reconcile UID counter
        // ------------------------------------------------------------------
        registry = new MaceRegistry(databaseManager, auditLogger, maceConfig);
        registry.loadFromDatabase();

        // Seed the UID counter: prefer the higher of what internal-data says vs DB.
        int internalCounter = internalData.getInt("next-uid-counter", 1);
        int registryCounter = registry.getNextUidCounter();
        int effectiveCounter = Math.max(internalCounter, registryCounter);
        registry.setNextUidCounter(effectiveCounter);
        getLogger().info("UID counter seeded to: " + effectiveCounter);

        // Safety reset: if the server was offline for a very long time and the
        // internal-data records a last scan that is very old, reset missing counts
        // to avoid false positives on first scan.
        handleStartupScanSafety();

        // ------------------------------------------------------------------
        // 7. Event listeners (Agent 2 classes)
        // ------------------------------------------------------------------
        PluginManager pm = getServer().getPluginManager();

        MaceIdentifier identifier  = new MaceIdentifier(this, maceConfig);
        DupeDetector   dupeDetector = new DupeDetector(registry, identifier, auditLogger, this);

        pm.registerEvents(new CraftController(this, maceConfig, registry, identifier, auditLogger), this);
        pm.registerEvents(new EnchantGuard(this, maceConfig, registry, identifier, auditLogger), this);
        pm.registerEvents(new DestructionWatcher(this, maceConfig, registry, identifier, auditLogger), this);
        pm.registerEvents(new RealTimeTracker(this, maceConfig, registry, identifier, dupeDetector, auditLogger), this);

        // ------------------------------------------------------------------
        // 8. Periodic scanner (Agent 2)
        // ------------------------------------------------------------------
        PeriodicScanner scanner = new PeriodicScanner(registry, identifier, dupeDetector, auditLogger, maceConfig, this);
        scanner.scheduleNextScan();

        // ------------------------------------------------------------------
        // 9. Commands (Agent 3)
        // ------------------------------------------------------------------
        MaceTabCompleter tabCompleter = new MaceTabCompleter(registry, maceConfig);
        MaceCommand      cmd          = new MaceCommand(registry, identifier, maceConfig, auditLogger, scanner, this);

        getCommand("mace").setExecutor(cmd);
        getCommand("mace").setTabCompleter(tabCompleter);

        // ------------------------------------------------------------------
        // 10. Startup message
        // ------------------------------------------------------------------
        getLogger().info("  __  __                  ____            _             _ ");
        getLogger().info(" |  \\/  | __ _  ___ ___  / ___|___  _ __ | |_ _ __ ___ | |");
        getLogger().info(" | |\\/| |/ _` |/ __/ _ \\| |   / _ \\| '_ \\| __| '__/ _ \\| |");
        getLogger().info(" | |  | | (_| | (_|  __/| |__| (_) | | | | |_| | | (_) | |");
        getLogger().info(" |_|  |_|\\__,_|\\___\\___| \\____\\___/|_| |_|\\__|_|  \\___/|_|");
        getLogger().info("");
        getLogger().info(" MaceControl+ v" + getDescription().getVersion() + " - created by nazedeshou");
        getLogger().info(" Adaptive mace tracking & management for Paper 1.21.10+");
        getLogger().info("");
        getLogger().info("[MaceControl+] Plugin active. Any untracked maces will be automatically removed. "
                + "Use /mace give or craft new maces to create tracked ones.");
        getLogger().info("[MaceControl+] " + registry.countActive() + " active mace(s) loaded from database.");
    }

    @Override
    public void onDisable() {
        // Persist the current UID counter back to internal-data.yml.
        if (internalData != null && registry != null) {
            internalData.set("next-uid-counter", registry.getNextUidCounter());
            saveInternalData();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("[MaceControl+] Disabled.");
    }

    // =========================================================================
    // Internal data (HMAC secret + scan scheduling state)
    // =========================================================================

    /**
     * Loads (or generates) {@code plugins/MaceControl/internal-data.yml}.
     *
     * <ul>
     *   <li>If the file does not exist, a fresh 64-character hex HMAC secret is generated
     *       with {@link SecureRandom} and the file is written.</li>
     *   <li>If the file exists but has no {@code hmac-secret} key, a new secret is generated
     *       and a warning is logged (all existing mace checksums are now invalid; the
     *       {@link MaceIdentifier} will recompute them at first encounter).</li>
     * </ul>
     */
    public void initInternalData() {
        getDataFolder().mkdirs();
        internalDataFile = new File(getDataFolder(), "internal-data.yml");

        if (!internalDataFile.exists()) {
            internalData = new YamlConfiguration();
            internalData.options().setHeader(java.util.List.of("AUTO-GENERATED — DO NOT EDIT"));
            String secret = generateHmacSecret();
            internalData.set("hmac-secret",            secret);
            internalData.set("next-uid-counter",       1);
            internalData.set("last-scan-completed-at", 0L);
            internalData.set("next-scan-scheduled-at", 0L);
            saveInternalData();
            getLogger().info("[MaceControl+] Generated new HMAC secret and created internal-data.yml.");
        } else {
            internalData = YamlConfiguration.loadConfiguration(internalDataFile);
            if (!internalData.contains("hmac-secret") || internalData.getString("hmac-secret", "").isBlank()) {
                String secret = generateHmacSecret();
                internalData.set("hmac-secret", secret);
                saveInternalData();
                getLogger().warning("[MaceControl+] hmac-secret was missing from internal-data.yml. "
                        + "A new secret has been generated. Existing mace checksums are now invalid "
                        + "and will be recomputed on first encounter.");
            }
        }
    }

    /**
     * Returns the HMAC secret stored in {@code internal-data.yml}.
     *
     * @return the 64-character hex HMAC secret
     */
    public String getHmacSecret() {
        if (internalData == null) return "";
        return internalData.getString("hmac-secret", "");
    }

    /**
     * Persists {@code internal-data.yml} to disk.
     *
     * <p>This is always called synchronously and is only done infrequently (startup,
     * shutdown, and UID counter updates). It must not be called on async threads.</p>
     */
    public void saveInternalData() {
        if (internalData == null || internalDataFile == null) return;
        try {
            internalData.save(internalDataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "[MaceControl+] Failed to save internal-data.yml.", e);
        }
    }

    /**
     * Updates a specific key in {@code internal-data.yml} and saves immediately.
     *
     * @param key   the YAML key to set
     * @param value the value to assign
     */
    public void setInternalData(String key, Object value) {
        if (internalData == null) return;
        internalData.set(key, value);
        saveInternalData();
    }

    /**
     * Returns the raw {@link FileConfiguration} for {@code internal-data.yml}.
     *
     * @return the internal data config
     */
    public FileConfiguration getInternalData() {
        return internalData;
    }

    // =========================================================================
    // Startup scan safety
    // =========================================================================

    /**
     * On startup, if the last completed scan is recorded in {@code internal-data.yml}
     * and is non-zero (meaning at least one scan has run before), log a notice.
     *
     * <p>As a conservative safety measure, all {@code missing_scan_count} values are reset
     * to 0 for ACTIVE maces to prevent false positives caused by extended server downtime
     * spanning multiple would-be scan cycles.</p>
     */
    private void handleStartupScanSafety() {
        if (internalData == null) return;
        long lastScan = internalData.getLong("last-scan-completed-at", 0L);
        if (lastScan > 0) {
            long ageSecs = Instant.now().getEpochSecond() - lastScan;
            long ageMins = ageSecs / 60;
            getLogger().info("[MaceControl+] Last scan completed " + ageMins + " minute(s) ago. "
                    + "Resetting missing_scan_count for all ACTIVE maces as startup safety measure.");
            for (MaceEntry entry : registry.getActiveEntries()) {
                registry.setMissingScanCount(entry.getUid(), 0);
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Generates a cryptographically random 64-character hexadecimal string for use
     * as the HMAC-SHA256 signing secret.
     *
     * @return a 64-character lowercase hex string
     */
    private String generateHmacSecret() {
        byte[] bytes = new byte[32]; // 256 bits
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the singleton plugin instance.
     *
     * @return the active {@link MaceControl} instance
     */
    public static MaceControl getInstance() {
        return instance;
    }

    /**
     * Returns the type-safe configuration wrapper.
     *
     * @return the {@link MaceConfig} instance
     */
    public MaceConfig getMaceConfig() {
        return maceConfig;
    }

    /**
     * Returns the central mace registry.
     *
     * @return the {@link MaceRegistry} instance
     */
    public MaceRegistry getRegistry() {
        return registry;
    }

    /**
     * Returns the audit logger.
     *
     * @return the {@link AuditLogger} instance
     */
    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    /**
     * Sends a message to all online players who are either OP or hold the
     * {@code macecontrol.admin} permission.
     *
     * @param message the message to send (supports colour codes)
     */
    public void notifyOps(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("macecontrol.admin")) {
                p.sendMessage(message);
            }
        }
    }
}
