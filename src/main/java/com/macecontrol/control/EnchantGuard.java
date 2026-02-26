package com.macecontrol.control;

import com.macecontrol.MaceControl;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.AuditLogger;
import com.macecontrol.data.MaceTier;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.tracking.MaceIdentifier;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the enchantment whitelist for LIMITED-tier maces at enchanting tables,
 * anvils, and grindstones.
 * <p>
 * <b>Enchanting table:</b> Allows the event; strips forbidden enchantments one tick
 * later and notifies the player.
 * <p>
 * <b>Anvil:</b> Inspects the would-be result before the player can take it; if any
 * forbidden enchantment is present, clears the result slot so the anvil produces
 * no output.
 * <p>
 * <b>Grindstone:</b> Allowed (removes enchantments rather than adding them), but
 * the event is logged for audit trail.
 * <p>
 * FULL-tier maces pass all checks unconditionally.
 */
public class EnchantGuard implements Listener {

    /** Sentinel value returned by {@link MaceConfig#getAllowedEnchantments(MaceTier)} when all are allowed. */
    private static final String ALL_SENTINEL = "ALL";

    private final MaceControl plugin;
    private final MaceConfig config;
    private final MaceRegistry registry;
    private final MaceIdentifier identifier;
    private final AuditLogger auditLogger;

    /**
     * Constructs a new EnchantGuard.
     *
     * @param plugin      the plugin instance
     * @param config      the plugin configuration
     * @param registry    the in-memory mace registry
     * @param identifier  the PDC read/write helper
     * @param auditLogger the audit-logging service
     */
    public EnchantGuard(MaceControl plugin, MaceConfig config, MaceRegistry registry,
                        MaceIdentifier identifier, AuditLogger auditLogger) {
        this.plugin      = plugin;
        this.config      = config;
        this.registry    = registry;
        this.identifier  = identifier;
        this.auditLogger = auditLogger;
    }

    // =========================================================================
    // Enchanting table
    // =========================================================================

    /**
     * Allows the enchanting event to proceed, then strips any forbidden enchantments
     * from the result one tick later (after Bukkit has applied them).
     * Notifies the player if any enchantments were removed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() != Material.MACE) return;
        if (!isLimitedMace(item)) return; // FULL tier or untracked — no restrictions

        Set<String> allowed = config.getAllowedEnchantments(MaceTier.LIMITED);
        if (allowed.contains(ALL_SENTINEL)) return; // unrestricted

        Player player = event.getEnchanter();
        String uid = identifier.getUid(item);

        // Schedule a 1-tick task to strip forbidden enchantments after Bukkit applies them
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // The item reference from the event is the live item in the player's inventory
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;

            List<Enchantment> toRemove = new ArrayList<>();
            for (Enchantment ench : meta.getEnchants().keySet()) {
                String enchKey = ench.getKey().getKey().toUpperCase();
                if (!allowed.contains(enchKey)) {
                    toRemove.add(ench);
                }
            }

            if (toRemove.isEmpty()) return;

            for (Enchantment ench : toRemove) {
                meta.removeEnchant(ench);
            }
            item.setItemMeta(meta);

            player.sendMessage("§7Some enchantments are restricted on this mace tier and were removed.");

            if (uid != null) {
                StringBuilder stripped = new StringBuilder();
                for (Enchantment e : toRemove) {
                    stripped.append(e.getKey().getKey()).append(" ");
                }
                auditLogger.log(uid, "ENCHANT_BLOCKED",
                        "Stripped at enchanting table: " + stripped.toString().trim(),
                        player.getUniqueId(), player.getName(), player.getLocation());
            }
        }, 1L);
    }

    // =========================================================================
    // Anvil
    // =========================================================================

    /**
     * Inspects the anvil's computed result. If the first slot holds a LIMITED mace
     * and the result would contain a forbidden enchantment, clears the result slot
     * and notifies the player.
     * <p>
     * Also re-applies mace lore if an anvil rename altered it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        ItemStack first = anvil.getItem(0);
        if (first == null || first.getType() != Material.MACE) return;
        if (!isLimitedMace(first)) {
            // FULL tier — re-apply lore in case rename changed it
            identifier.reapplyLoreIfNeeded(first);
            return;
        }

        String uid = identifier.getUid(first);

        // Re-apply lore if needed (player may have renamed via anvil)
        identifier.reapplyLoreIfNeeded(first);

        ItemStack result = event.getResult();
        if (result == null) return;

        Set<String> allowed = config.getAllowedEnchantments(MaceTier.LIMITED);
        if (allowed.contains(ALL_SENTINEL)) return;

        // Check result enchantments for forbidden ones
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) return;

        for (Enchantment ench : resultMeta.getEnchants().keySet()) {
            String enchKey = ench.getKey().getKey().toUpperCase();
            if (!allowed.contains(enchKey)) {
                // Forbidden enchantment would be applied — block output
                event.setResult(null);

                // Notify the player via a 1-tick delayed message
                // (cannot send synchronously inside PrepareAnvilEvent on all server versions)
                String forbiddenName = ench.getKey().getKey();
                if (event.getView().getPlayer() instanceof Player player) {
                    plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                            player.sendMessage("§cThis mace cannot receive "
                                    + formatEnchantName(forbiddenName) + "."), 1L);

                    if (uid != null) {
                        auditLogger.log(uid, "ENCHANT_BLOCKED",
                                "Blocked at anvil: " + forbiddenName,
                                player.getUniqueId(), player.getName(), player.getLocation());
                    }
                } else if (uid != null) {
                    auditLogger.log(uid, "ENCHANT_BLOCKED",
                            "Blocked at anvil: " + forbiddenName);
                }
                return; // One forbidden enchantment is enough to block entirely
            }
        }
    }

    // =========================================================================
    // Grindstone
    // =========================================================================

    /**
     * Logs grindstone usage when a tracked mace is placed in the grindstone.
     * Grindstone removes enchantments rather than adding them, so no blocking
     * is required — only an audit trail entry.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        GrindstoneInventory grindstone = event.getInventory();

        for (int slot = 0; slot <= 1; slot++) {
            ItemStack item = grindstone.getItem(slot);
            if (item == null || item.getType() != Material.MACE) continue;
            String uid = identifier.getUid(item);
            if (uid == null) continue;

            // Log grindstone usage (allowed but audited)
            if (event.getView().getPlayer() instanceof Player player) {
                auditLogger.log(uid, "GRINDSTONE_USE",
                        "Mace placed in grindstone by " + player.getName(),
                        player.getUniqueId(), player.getName(), player.getLocation());
            } else {
                auditLogger.log(uid, "GRINDSTONE_USE", "Mace placed in grindstone");
            }
        }
    }

    /**
     * Catches the grindstone output click to log when the player takes the de-enchanted mace.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrindstoneOutputClick(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.GRINDSTONE) return;
        if (event.getSlot() != 2) return; // slot 2 = output slot
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() != Material.MACE) return;

        String uid = identifier.getUid(result);
        if (uid == null) return;

        auditLogger.log(uid, "GRINDSTONE_USE",
                "Mace taken from grindstone output by " + player.getName(),
                player.getUniqueId(), player.getName(), player.getLocation());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns {@code true} if the item is a tracked mace with LIMITED tier.
     *
     * @param item the ItemStack to check
     * @return {@code true} if it is a LIMITED-tier tracked mace
     */
    private boolean isLimitedMace(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return false;
        MaceTier tier = identifier.getTier(item);
        return tier == MaceTier.LIMITED;
    }

    /**
     * Formats an enchantment key (e.g. "wind_burst") into a human-readable name
     * (e.g. "Wind Burst") for player-facing messages.
     *
     * @param key the enchantment key string (lowercase, underscore-separated)
     * @return capitalized, space-separated name
     */
    private String formatEnchantName(String key) {
        if (key == null || key.isEmpty()) return key;
        String[] words = key.toLowerCase().replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1));
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
