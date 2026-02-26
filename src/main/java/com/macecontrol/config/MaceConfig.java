package com.macecontrol.config;

import com.macecontrol.data.MaceTier;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Type-safe, reload-aware wrapper for {@code config.yml}.
 *
 * <p>All values are read directly from the plugin's {@link org.bukkit.configuration.file.FileConfiguration}
 * at call time so that a {@code /mace reload} (which calls {@link JavaPlugin#reloadConfig()})
 * is immediately reflected without requiring a server restart.</p>
 *
 * <p>This class intentionally has no mutable state of its own — it is a thin, stateless
 * delegate to Bukkit's config API.</p>
 */
public final class MaceConfig {

    private final JavaPlugin plugin;

    /**
     * Constructs a {@link MaceConfig} bound to the given plugin.
     *
     * @param plugin the owning plugin; its {@link JavaPlugin#getConfig()} is consulted for all values
     */
    public MaceConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    // Slot management
    // =========================================================================

    /**
     * Returns the maximum number of {@link com.macecontrol.data.MaceTier#LIMITED LIMITED}
     * maces allowed in circulation simultaneously.
     *
     * <p>Config key: {@code max-normal-mace-limit} (default: 4)</p>
     *
     * @return the LIMITED slot cap
     */
    public int getMaxNormalMaceLimit() {
        return plugin.getConfig().getInt("max-normal-mace-limit", 4);
    }

    /**
     * Returns the maximum number of {@link com.macecontrol.data.MaceTier#FULL FULL}
     * maces allowed in circulation simultaneously.
     *
     * <p>Config key: {@code max-full-mace-limit} (default: 1)</p>
     *
     * @return the FULL slot cap
     */
    public int getMaxFullMaceLimit() {
        return plugin.getConfig().getInt("max-full-mace-limit", 1);
    }

    /**
     * Returns the total mace capacity across all tiers
     * ({@link #getMaxNormalMaceLimit()} + {@link #getMaxFullMaceLimit()}).
     *
     * @return total slot cap
     */
    public int getMaxTotalMaces() {
        return getMaxNormalMaceLimit() + getMaxFullMaceLimit();
    }

    // =========================================================================
    // Scan settings
    // =========================================================================

    /**
     * Returns the minimum interval in minutes between automatic deep scans.
     *
     * <p>Config key: {@code scan.interval-min-minutes} (default: 120)</p>
     *
     * @return minimum scan interval in minutes
     */
    public int getScanIntervalMinMinutes() {
        return plugin.getConfig().getInt("scan.interval-min-minutes", 120);
    }

    /**
     * Returns the maximum interval in minutes between automatic deep scans.
     *
     * <p>Config key: {@code scan.interval-max-minutes} (default: 360)</p>
     *
     * @return maximum scan interval in minutes
     */
    public int getScanIntervalMaxMinutes() {
        return plugin.getConfig().getInt("scan.interval-max-minutes", 360);
    }

    /**
     * Returns how many consecutive scans (where the mace location was accessible but the
     * mace was not found) must occur before the mace is declared destroyed.
     *
     * <p>Config key: {@code scan.missed-scans-to-destroy} (default: 4)</p>
     *
     * @return missed scan threshold
     */
    public int getMissedScansToDestroy() {
        return plugin.getConfig().getInt("scan.missed-scans-to-destroy", 4);
    }

    /**
     * Returns whether the scanner should temporarily force-load chunks that contain a
     * mace's last known location.
     *
     * <p>Config key: {@code scan.force-load-chunks} (default: false)</p>
     *
     * @return {@code true} if force-loading is enabled
     */
    public boolean isForceLoadChunks() {
        return plugin.getConfig().getBoolean("scan.force-load-chunks", false);
    }

    /**
     * Returns whether the scanner should read offline player data files.
     *
     * <p>Config key: {@code scan.scan-offline-players} (default: true)</p>
     *
     * @return {@code true} if offline player scanning is enabled
     */
    public boolean isScanOfflinePlayers() {
        return plugin.getConfig().getBoolean("scan.scan-offline-players", true);
    }

    /**
     * Returns the number of minutes after which a mace's location data is considered
     * stale and should be re-verified before reporting it to admins.
     *
     * <p>Config key: {@code scan.stale-threshold-minutes} (default: 30)</p>
     *
     * @return staleness threshold in minutes
     */
    public int getStaleThresholdMinutes() {
        return plugin.getConfig().getInt("scan.stale-threshold-minutes", 30);
    }

    // =========================================================================
    // Enchantment tiers
    // =========================================================================

    /**
     * Returns the set of allowed enchantment name strings for the given tier.
     *
     * <p>For {@link MaceTier#FULL}, the set will contain the special sentinel value
     * {@code "ALL"}, meaning no restrictions apply. Callers should check for this:
     * <pre>
     *     if (getAllowedEnchantments(FULL).contains("ALL")) { // no restrictions }
     * </pre>
     *
     * <p>Config key: {@code tiers.LIMITED.allowed-enchantments} or
     * {@code tiers.FULL.allowed-enchantments}</p>
     *
     * @param tier the tier to query
     * @return an unmodifiable set of allowed enchantment name strings (upper-cased);
     *         an empty set means all enchantments are blocked
     */
    public Set<String> getAllowedEnchantments(MaceTier tier) {
        if (tier == null) return Collections.emptySet();
        String key = "tiers." + tier.name() + ".allowed-enchantments";

        // The YAML value may be a string scalar ("ALL") or a list.
        Object raw = plugin.getConfig().get(key);
        if (raw == null) {
            // FULL defaults to all-permitted; LIMITED defaults to empty (blocked).
            return tier == MaceTier.FULL
                    ? Collections.singleton("ALL")
                    : Collections.emptySet();
        }

        if (raw instanceof String s) {
            return Collections.singleton(s.trim().toUpperCase());
        }

        if (raw instanceof List<?> list) {
            Set<String> result = new LinkedHashSet<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s.trim().toUpperCase());
                }
            }
            return Collections.unmodifiableSet(result);
        }

        return Collections.emptySet();
    }

    /**
     * Returns whether all enchantments are permitted for the given tier (i.e. the
     * allowed-enchantments config value is the sentinel {@code "ALL"}).
     *
     * @param tier the tier to check
     * @return {@code true} if there are no enchantment restrictions
     */
    public boolean isAllEnchantmentsAllowed(MaceTier tier) {
        return getAllowedEnchantments(tier).contains("ALL");
    }

    /**
     * Returns the display name string for the given tier.
     *
     * <p>Config key: {@code tiers.LIMITED.display-name} / {@code tiers.FULL.display-name}</p>
     *
     * @param tier the tier
     * @return the display name, with colour codes if configured
     */
    public String getTierDisplayName(MaceTier tier) {
        if (tier == null) return "Unknown";
        String key = "tiers." + tier.name() + ".display-name";
        return plugin.getConfig().getString(key, tier.name());
    }

    // =========================================================================
    // Lore format
    // =========================================================================

    /**
     * Returns the configured lore lines for the given tier.
     *
     * <p>Each line may contain the placeholder {@code %uid%} which should be replaced
     * by the mace's actual UID before applying the lore to an item stack.</p>
     *
     * <p>Config key: {@code display.lore-format.LIMITED} / {@code display.lore-format.FULL}</p>
     *
     * @param tier the tier whose lore format is requested
     * @return an unmodifiable list of lore lines (with {@code %uid%} placeholder intact)
     */
    public List<String> getLoreFormat(MaceTier tier) {
        if (tier == null) return Collections.emptyList();
        String key = "display.lore-format." + tier.name();
        List<String> lines = plugin.getConfig().getStringList(key);
        return lines.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(lines);
    }

    // =========================================================================
    // Crafting messages
    // =========================================================================

    /**
     * Returns the raw public craft message template with placeholders intact.
     *
     * <p>Config key: {@code notifications.public-craft-message}</p>
     *
     * @return the raw template string
     */
    public String getPublicCraftMessage() {
        return plugin.getConfig().getString(
                "notifications.public-craft-message",
                "§6⚔ {player} has obtained a mace! §7({normal_count}/{normal_max} standard §8| §b{full_count}/{full_max} legendary§7)"
        );
    }

    /**
     * Returns the formatted public craft message with all placeholders replaced.
     *
     * <p>Placeholders replaced:
     * {@code {player}}, {@code {normal_count}}, {@code {normal_max}},
     * {@code {full_count}}, {@code {full_max}}, {@code {total_count}}, {@code {total_max}}</p>
     *
     * @param playerName  the name of the player who obtained the mace
     * @param normalCount current number of active LIMITED maces
     * @param normalMax   configured LIMITED slot cap
     * @param fullCount   current number of active FULL maces
     * @param fullMax     configured FULL slot cap
     * @return the formatted message string, ready to broadcast
     */
    public String formatCraftMessage(String playerName, int normalCount, int normalMax,
                                     int fullCount, int fullMax) {
        return getPublicCraftMessage()
                .replace("{player}",        playerName    != null ? playerName : "Unknown")
                .replace("{normal_count}",  String.valueOf(normalCount))
                .replace("{normal_max}",    String.valueOf(normalMax))
                .replace("{full_count}",    String.valueOf(fullCount))
                .replace("{full_max}",      String.valueOf(fullMax))
                .replace("{total_count}",   String.valueOf(normalCount + fullCount))
                .replace("{total_max}",     String.valueOf(normalMax + fullMax));
    }

    /**
     * Returns the message sent to a player who attempts to craft a mace when all
     * LIMITED slots are full, with {@code {count}} and {@code {max}} replaced.
     *
     * <p>Config key: {@code crafting.slot-full-message}</p>
     *
     * @param count current number of active LIMITED maces
     * @param max   configured LIMITED slot cap
     * @return the formatted slot-full message
     */
    public String getSlotFullMessage(int count, int max) {
        return plugin.getConfig()
                .getString("crafting.slot-full-message",
                        "§cAll standard mace slots are currently full ({count}/{max}). A slot may open if a mace is destroyed.")
                .replace("{count}", String.valueOf(count))
                .replace("{max}",   String.valueOf(max));
    }

    /**
     * Returns the formatted public slot-open message with all placeholders replaced.
     *
     * <p>Placeholders replaced:
     * {@code {tier}}, {@code {normal_count}}, {@code {normal_max}},
     * {@code {full_count}}, {@code {full_max}}, {@code {total_count}}, {@code {total_max}}</p>
     *
     * <p>Config key: {@code notifications.public-slot-open-message}</p>
     *
     * @param tier        display name of the tier whose slot opened (e.g. {@code "Standard"})
     * @param normalCount current active LIMITED count
     * @param normalMax   LIMITED slot cap
     * @param fullCount   current active FULL count
     * @param fullMax     FULL slot cap
     * @return the formatted slot-open message
     */
    public String getPublicSlotOpenMessage(String tier, int normalCount, int normalMax,
                                           int fullCount, int fullMax) {
        return plugin.getConfig()
                .getString("notifications.public-slot-open-message",
                        "§6A {tier} mace slot has opened! §7({normal_count}/{normal_max} standard §8| §b{full_count}/{full_max} legendary§7)")
                .replace("{tier}",          tier           != null ? tier : "Unknown")
                .replace("{normal_count}",  String.valueOf(normalCount))
                .replace("{normal_max}",    String.valueOf(normalMax))
                .replace("{full_count}",    String.valueOf(fullCount))
                .replace("{full_max}",      String.valueOf(fullMax))
                .replace("{total_count}",   String.valueOf(normalCount + fullCount))
                .replace("{total_max}",     String.valueOf(normalMax + fullMax));
    }

    // =========================================================================
    // Notification toggles
    // =========================================================================

    /**
     * Returns whether online ops/admins should be notified when a mace is crafted.
     *
     * <p>Config key: {@code notifications.ops-on-craft} (default: true)</p>
     */
    public boolean isOpsOnCraft() {
        return plugin.getConfig().getBoolean("notifications.ops-on-craft", true);
    }

    /**
     * Returns whether online ops/admins should be notified when a mace is destroyed.
     *
     * <p>Config key: {@code notifications.ops-on-destroy} (default: true)</p>
     */
    public boolean isOpsOnDestroy() {
        return plugin.getConfig().getBoolean("notifications.ops-on-destroy", true);
    }

    /**
     * Returns whether online ops/admins should be notified when a duplicate mace is detected.
     *
     * <p>Config key: {@code notifications.ops-on-dupe} (default: true)</p>
     */
    public boolean isOpsOnDupe() {
        return plugin.getConfig().getBoolean("notifications.ops-on-dupe", true);
    }

    /**
     * Returns whether online ops/admins should be notified when a periodic scan completes.
     *
     * <p>Config key: {@code notifications.ops-on-scan-complete} (default: false)</p>
     */
    public boolean isOpsOnScanComplete() {
        return plugin.getConfig().getBoolean("notifications.ops-on-scan-complete", false);
    }

    /**
     * Returns whether the server should broadcast a public message when a mace slot
     * opens after destruction.
     *
     * <p>Config key: {@code notifications.public-on-slot-open} (default: false)</p>
     */
    public boolean isPublicOnSlotOpen() {
        return plugin.getConfig().getBoolean("notifications.public-on-slot-open", false);
    }

    // =========================================================================
    // Audit settings
    // =========================================================================

    /**
     * Returns whether audit events should also be written to the flat-file log.
     *
     * <p>Config key: {@code audit.log-to-file} (default: true)</p>
     */
    public boolean isLogToFile() {
        return plugin.getConfig().getBoolean("audit.log-to-file", true);
    }

    /**
     * Returns the maximum number of audit entries to retain per mace.
     * Older entries are pruned after each write.
     *
     * <p>Config key: {@code audit.max-history-per-mace} (default: 200)</p>
     */
    public int getMaxHistoryPerMace() {
        return plugin.getConfig().getInt("audit.max-history-per-mace", 200);
    }

    // =========================================================================
    // Performance
    // =========================================================================

    /**
     * Returns the number of chunks to process per server tick during a deep scan.
     *
     * <p>Config key: {@code performance.chunks-per-tick} (default: 50)</p>
     */
    public int getChunksPerTick() {
        return plugin.getConfig().getInt("performance.chunks-per-tick", 50);
    }
}
