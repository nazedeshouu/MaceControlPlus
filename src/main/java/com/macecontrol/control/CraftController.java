package com.macecontrol.control;

import com.macecontrol.MaceControl;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.AuditLogger;
import com.macecontrol.data.MaceEntry;
import com.macecontrol.data.MaceLocationType;
import com.macecontrol.data.MaceTier;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.data.MaceStatus;
import com.macecontrol.tracking.MaceIdentifier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;

/**
 * Enforces the mace slot system at the crafting table.
 * <p>
 * <b>PrepareItemCraftEvent:</b> Previews slot availability. If LIMITED slots are full,
 * the result is cleared so the player sees no output — discouraging the craft before
 * it is attempted.
 * <p>
 * <b>CraftItemEvent:</b> Atomic enforcement. Re-checks slot count after the item is
 * confirmed to prevent race conditions from two players crafting simultaneously.
 * On success: stamps PDC, applies lore, registers in the registry, logs audit,
 * and broadcasts the server-wide announcement.
 * <p>
 * Also exposes {@link #issueTrackedMace(Player, MaceTier, MaceRegistry, MaceIdentifier,
 * MaceConfig, AuditLogger, MaceControl, boolean)} as a static helper reused by
 * the command handler for {@code /mace give}.
 */
public class CraftController implements Listener {

    private final MaceControl plugin;
    private final MaceConfig config;
    private final MaceRegistry registry;
    private final MaceIdentifier identifier;
    private final AuditLogger auditLogger;

    /**
     * Constructs a new CraftController.
     *
     * @param plugin      the plugin instance
     * @param config      the plugin configuration
     * @param registry    the in-memory mace registry
     * @param identifier  the PDC read/write helper
     * @param auditLogger the audit-logging service
     */
    public CraftController(MaceControl plugin, MaceConfig config, MaceRegistry registry,
                           MaceIdentifier identifier, AuditLogger auditLogger) {
        this.plugin      = plugin;
        this.config      = config;
        this.registry    = registry;
        this.identifier  = identifier;
        this.auditLogger = auditLogger;
    }

    // =========================================================================
    // PrepareItemCraftEvent — preview enforcement
    // =========================================================================

    /**
     * Clears the crafting result preview when LIMITED mace slots are full,
     * so the player cannot see a craftable output in the result slot.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() != Material.MACE) return;

        int currentLimited = registry.countActiveByTier(MaceTier.LIMITED);
        if (currentLimited >= config.getMaxNormalMaceLimit()) {
            event.getInventory().setResult(null);
            // Notify the viewer (always a Player for crafting table)
            if (event.getView().getPlayer() instanceof Player player) {
                player.sendMessage(config.getSlotFullMessage(
                        registry.countActive(), config.getMaxTotalMaces()));
            }
        }
    }

    // =========================================================================
    // CraftItemEvent — atomic enforcement and registration
    // =========================================================================

    /**
     * Atomically enforces the slot cap when the player confirms a mace craft.
     * On success, stamps the item with PDC data, registers it, and broadcasts
     * the server-wide announcement.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player crafter)) return;
        CraftingInventory craftInv = event.getInventory();
        ItemStack result = craftInv.getResult();
        if (result == null || result.getType() != Material.MACE) return;

        // --- Atomic slot check (race-condition protection) ---
        synchronized (registry) {
            int currentLimited = registry.countActiveByTier(MaceTier.LIMITED);
            if (currentLimited >= config.getMaxNormalMaceLimit()) {
                event.setCancelled(true);
                crafter.sendMessage(config.getSlotFullMessage(
                        registry.countActive(), config.getMaxTotalMaces()));
                return;
            }
        }

        // --- Handle shift-click (bulk craft) ---
        // Shift-click crafts as many as the inventory can hold. We cancel and re-give
        // exactly one mace to ensure each gets a unique UID. If the player tries to
        // shift-click, we cancel and give one tracked mace per available slot up to 1
        // (maces don't stack, so effectively always 1 per shift-click).
        if (event.isShiftClick()) {
            event.setCancelled(true);
            // Consume one set of ingredients manually then give one tracked mace
            // by calling our registration logic and placing in inventory
            registerAndGiveCraftedMace(crafter, craftInv, true);
            return;
        }

        // --- Normal single craft ---
        event.setCancelled(true); // Cancel so we can replace the result with a stamped item
        registerAndGiveCraftedMace(crafter, craftInv, false);
    }

    // =========================================================================
    // Static helper — issueTrackedMace (reused by /mace give)
    // =========================================================================

    /**
     * Creates and gives a new tracked mace directly to a player's inventory.
     * <p>
     * This method is used by both the admin command ({@code /mace give}) and
     * indirectly by the crafting flow. It handles registry registration, PDC
     * stamping, lore application, audit logging, and the optional public broadcast.
     * <p>
     * If the player's inventory is full, the mace is dropped at their feet.
     *
     * @param recipient   the player to receive the mace
     * @param tier        the enchantment tier (LIMITED or FULL)
     * @param registry    the mace registry
     * @param identifier  the PDC helper
     * @param config      the plugin config
     * @param auditLogger the audit logger
     * @param plugin      the plugin instance
     * @param silent      if {@code true}, suppress the public broadcast
     *                    (used when the admin wants a quiet give)
     */
    public static void issueTrackedMace(Player recipient, MaceTier tier,
                                        MaceRegistry registry, MaceIdentifier identifier,
                                        MaceConfig config, AuditLogger auditLogger,
                                        MaceControl plugin, boolean silent) {
        // --- Slot enforcement for FULL tier ---
        if (tier == MaceTier.FULL) {
            int currentFull = registry.countActiveByTier(MaceTier.FULL);
            if (currentFull >= config.getMaxFullMaceLimit()) {
                recipient.sendMessage("§cCannot issue a FULL-tier mace: max FULL tier slots ("
                        + config.getMaxFullMaceLimit() + ") already in use.");
                return;
            }
        }

        // --- Slot enforcement for LIMITED tier ---
        if (tier == MaceTier.LIMITED) {
            int currentLimited = registry.countActiveByTier(MaceTier.LIMITED);
            if (currentLimited >= config.getMaxNormalMaceLimit()) {
                recipient.sendMessage(config.getSlotFullMessage(
                        registry.countActive(), config.getMaxTotalMaces()));
                return;
            }
        }

        // --- Generate UID and create item ---
        String uid = registry.generateNextUid();
        long createdAt = Instant.now().getEpochSecond();
        ItemStack mace = identifier.createTrackedMace(uid, tier);

        // --- Register in registry ---
        MaceEntry entry = new MaceEntry();
        entry.setUid(uid);
        entry.setTier(tier);
        entry.setStatus(MaceStatus.ACTIVE);
        entry.setCreatedAt(createdAt);
        entry.setCreatedByUuid(recipient.getUniqueId());
        entry.setCreatedByName(recipient.getName());
        entry.setLocationType(MaceLocationType.PLAYER_INVENTORY);
        entry.setLocationWorld(recipient.getWorld().getName());
        entry.setLocationX(recipient.getLocation().getBlockX());
        entry.setLocationY(recipient.getLocation().getBlockY());
        entry.setLocationZ(recipient.getLocation().getBlockZ());
        entry.setLocationHolderUuid(recipient.getUniqueId());
        entry.setLocationHolderName(recipient.getName());
        entry.setLocationUpdatedAt(createdAt);
        entry.setLastVerifiedAt(createdAt);
        entry.setMissingScanCount(0);
        registry.register(entry);

        // --- Give to player ---
        if (recipient.getInventory().firstEmpty() == -1) {
            // No inventory space — drop at player's feet
            recipient.getWorld().dropItemNaturally(recipient.getLocation(), mace);
            recipient.sendMessage("§e[MaceControl] Your inventory is full. Mace " + uid
                    + " dropped at your feet.");
        } else {
            recipient.getInventory().addItem(mace);
        }

        // --- Audit log ---
        auditLogger.log(uid, "ISSUED",
                "Issued " + tier.name() + " mace to " + recipient.getName(),
                recipient.getUniqueId(), recipient.getName(), recipient.getLocation());

        // --- Ops notification ---
        if (config.isOpsOnCraft()) {
            plugin.notifyOps("§e[MaceControl] " + tier.name() + " mace " + uid
                    + " issued to " + recipient.getName() + ".");
        }

        // --- Public broadcast ---
        if (!silent) {
            int normalCount = registry.countActiveByTier(MaceTier.LIMITED);
            int fullCount   = registry.countActiveByTier(MaceTier.FULL);
            String msg = config.formatCraftMessage(
                    recipient.getDisplayName(),
                    normalCount, config.getMaxNormalMaceLimit(),
                    fullCount, config.getMaxFullMaceLimit());
            Bukkit.broadcastMessage(msg);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Handles the full registration flow for a mace crafted at a crafting table.
     * Consumes one set of crafting ingredients, generates a UID, stamps the item,
     * registers it, and gives it to the crafter.
     *
     * @param crafter    the player who crafted the mace
     * @param craftInv   the crafting inventory (used to consume ingredients)
     * @param shiftClick whether this was triggered by a shift-click
     */
    private void registerAndGiveCraftedMace(Player crafter, CraftingInventory craftInv,
                                             boolean shiftClick) {
        // Atomically claim a slot
        String uid;
        synchronized (registry) {
            int currentLimited = registry.countActiveByTier(MaceTier.LIMITED);
            if (currentLimited >= config.getMaxNormalMaceLimit()) {
                crafter.sendMessage(config.getSlotFullMessage(
                        registry.countActive(), config.getMaxTotalMaces()));
                return;
            }
            uid = registry.generateNextUid();
        }

        long createdAt = Instant.now().getEpochSecond();

        // Create the tracked mace item
        ItemStack mace = identifier.createTrackedMace(uid, MaceTier.LIMITED);

        // Register in the registry before giving (prevents any scan window issues)
        MaceEntry entry = new MaceEntry();
        entry.setUid(uid);
        entry.setTier(MaceTier.LIMITED);
        entry.setStatus(MaceStatus.ACTIVE);
        entry.setCreatedAt(createdAt);
        entry.setCreatedByUuid(crafter.getUniqueId());
        entry.setCreatedByName(crafter.getName());
        entry.setLocationType(MaceLocationType.PLAYER_INVENTORY);
        entry.setLocationWorld(crafter.getWorld().getName());
        entry.setLocationX(crafter.getLocation().getBlockX());
        entry.setLocationY(crafter.getLocation().getBlockY());
        entry.setLocationZ(crafter.getLocation().getBlockZ());
        entry.setLocationHolderUuid(crafter.getUniqueId());
        entry.setLocationHolderName(crafter.getName());
        entry.setLocationUpdatedAt(createdAt);
        entry.setLastVerifiedAt(createdAt);
        entry.setMissingScanCount(0);
        registry.register(entry);

        // Consume crafting ingredients (one set for one mace)
        consumeIngredients(craftInv);

        // Give the mace
        if (crafter.getInventory().firstEmpty() == -1) {
            crafter.getWorld().dropItemNaturally(crafter.getLocation(), mace);
            crafter.sendMessage("§e[MaceControl] Your inventory is full. Mace " + uid
                    + " dropped at your feet.");
        } else {
            crafter.getInventory().addItem(mace);
        }

        // Audit log
        auditLogger.log(uid, "CRAFTED",
                "Crafted by " + crafter.getName() + (shiftClick ? " (shift-click)" : ""),
                crafter.getUniqueId(), crafter.getName(), crafter.getLocation());

        // Ops notification
        if (config.isOpsOnCraft()) {
            plugin.notifyOps("§e[MaceControl] Mace " + uid + " crafted by "
                    + crafter.getName() + ".");
        }

        // Public broadcast — always sent, cannot be disabled
        int normalCount = registry.countActiveByTier(MaceTier.LIMITED);
        int fullCount   = registry.countActiveByTier(MaceTier.FULL);
        String msg = config.formatCraftMessage(
                crafter.getDisplayName(),
                normalCount, config.getMaxNormalMaceLimit(),
                fullCount, config.getMaxFullMaceLimit());
        Bukkit.broadcastMessage(msg);
    }

    /**
     * Consumes one set of crafting ingredients from the crafting matrix.
     * Decrements the stack size of each ingredient slot by 1.
     *
     * @param craftInv the crafting inventory whose matrix to consume from
     */
    private void consumeIngredients(CraftingInventory craftInv) {
        ItemStack[] matrix = craftInv.getMatrix();
        for (int i = 0; i < matrix.length; i++) {
            ItemStack ingredient = matrix[i];
            if (ingredient == null || ingredient.getType() == Material.AIR) continue;
            if (ingredient.getAmount() > 1) {
                ingredient.setAmount(ingredient.getAmount() - 1);
            } else {
                matrix[i] = null;
            }
        }
        craftInv.setMatrix(matrix);
    }
}
