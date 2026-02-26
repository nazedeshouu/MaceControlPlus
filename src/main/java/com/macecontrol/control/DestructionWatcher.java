package com.macecontrol.control;

import com.macecontrol.MaceControl;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.AuditLogger;
import com.macecontrol.data.MaceEntry;
import com.macecontrol.data.MaceTier;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.data.MaceStatus;
import com.macecontrol.tracking.MaceIdentifier;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Detects and records the destruction of tracked maces from all environmental causes.
 * <p>
 * <b>Monitored destruction causes:</b>
 * <ul>
 *   <li>Despawn (item entity timer expiry)</li>
 *   <li>Fire and lava damage to item entities</li>
 *   <li>Cactus damage to item entities</li>
 *   <li>Void (entity removal below the world)</li>
 *   <li>Block explosions and entity explosions</li>
 *   <li>Container blocks burned by fire</li>
 * </ul>
 * <p>
 * Each destruction event:
 * <ol>
 *   <li>Marks the mace as {@link MaceStatus#DESTROYED} in the registry.</li>
 *   <li>Logs an audit entry with the cause.</li>
 *   <li>Optionally notifies ops (per config).</li>
 *   <li>Optionally broadcasts a public slot-open announcement (per config).</li>
 * </ol>
 */
public class DestructionWatcher implements Listener {

    private final MaceControl plugin;
    private final MaceConfig config;
    private final MaceRegistry registry;
    private final MaceIdentifier identifier;
    private final AuditLogger auditLogger;

    /**
     * Constructs a new DestructionWatcher.
     *
     * @param plugin      the plugin instance
     * @param config      the plugin configuration
     * @param registry    the in-memory mace registry
     * @param identifier  the PDC read/write helper
     * @param auditLogger the audit-logging service
     */
    public DestructionWatcher(MaceControl plugin, MaceConfig config, MaceRegistry registry,
                               MaceIdentifier identifier, AuditLogger auditLogger) {
        this.plugin      = plugin;
        this.config      = config;
        this.registry    = registry;
        this.identifier  = identifier;
        this.auditLogger = auditLogger;
    }

    // =========================================================================
    // Item Despawn
    // =========================================================================

    /**
     * Detects a mace item entity despawning due to the 5-minute ground-item timer.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (item.getType() != Material.MACE) return;
        String uid = identifier.getUid(item);
        if (uid == null) return;
        markDestroyed(uid, "DESPAWN", event.getLocation(), getEntryTier(uid));
    }

    // =========================================================================
    // Entity Damage (fire, lava, cactus, void, explosions)
    // =========================================================================

    /**
     * Detects lethal damage to mace item entities from environmental causes.
     * Checks the damage cause and, if it reaches the entity's health threshold,
     * marks the mace as destroyed.
     * <p>
     * Note: Item entities in Bukkit have 1 HP. Any damage that would kill them
     * (i.e. damage >= remaining health) triggers destruction. We listen at MONITOR
     * priority and check if the entity would have died.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item itemEntity)) return;
        ItemStack item = itemEntity.getItemStack();
        if (item.getType() != Material.MACE) return;

        EntityDamageEvent.DamageCause cause = event.getCause();
        if (!isDestructiveCause(cause)) return;

        // Check if the damage is lethal (item entity health is typically 5.0 but can be lower)
        double healthAfter = itemEntity.getHealth() - event.getFinalDamage();
        if (healthAfter > 0) return; // Entity survives this hit

        String uid = identifier.getUid(item);
        if (uid == null) return;

        String causeStr = mapDamageCause(cause);
        markDestroyed(uid, causeStr, itemEntity.getLocation(), getEntryTier(uid));
    }

    // =========================================================================
    // Paper EntityRemoveEvent (authoritative destruction detection)
    // =========================================================================

    /**
     * Uses Paper's {@link EntityRemoveEvent} to catch mace item entity removals
     * caused by destruction (burning, despawn, void, etc.).
     * This complements {@link #onEntityDamage} for cases where no damage event fires.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item itemEntity)) return;
        ItemStack item = itemEntity.getItemStack();
        if (item.getType() != Material.MACE) return;

        EntityRemoveEvent.Cause removeCause = event.getCause();
        String causeStr = mapEntityRemoveCause(removeCause);
        if (causeStr == null) return; // Not a destructive removal (e.g. plugin teleport, unload)

        String uid = identifier.getUid(item);
        if (uid == null) return;

        // Guard against double-counting with EntityDamageEvent
        MaceEntry entry = registry.getEntry(uid);
        if (entry == null || entry.getStatus() == MaceStatus.DESTROYED
                || entry.getStatus() == MaceStatus.REVOKED) return;

        markDestroyed(uid, causeStr, itemEntity.getLocation(), getEntryTier(uid));
    }

    // =========================================================================
    // Block Burn (container fire)
    // =========================================================================

    /**
     * Detects maces that were stored inside a container block that caught fire and burned.
     * The registry is checked for any maces whose last-known location matches the
     * burning block.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder)) return;

        checkRegistryForContainerDestruction(block.getLocation(), "FIRE");
    }

    // =========================================================================
    // Block Explosion
    // =========================================================================

    /**
     * Detects maces stored in containers destroyed by a block explosion (TNT, etc.).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            BlockState state = block.getState();
            if (!(state instanceof InventoryHolder)) continue;
            checkRegistryForContainerDestruction(block.getLocation(), "EXPLOSION");
        }
    }

    // =========================================================================
    // Entity Explosion (creeper, TNT minecart, wither, etc.)
    // =========================================================================

    /**
     * Detects maces stored in containers destroyed by an entity explosion.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            BlockState state = block.getState();
            if (!(state instanceof InventoryHolder)) continue;
            checkRegistryForContainerDestruction(block.getLocation(), "EXPLOSION");
        }
    }

    // =========================================================================
    // Core destruction marker
    // =========================================================================

    /**
     * Marks a mace as DESTROYED in the registry, logs the audit event, notifies ops,
     * and optionally broadcasts the public slot-open message.
     *
     * @param uid      the mace UID
     * @param cause    human-readable destruction cause (e.g. "LAVA", "FIRE", "DESPAWN")
     * @param location the world location where destruction occurred
     * @param tier     the tier of the destroyed mace, or {@code null} if unknown
     */
    private void markDestroyed(String uid, String cause, Location location, MaceTier tier) {
        // Guard: only process ACTIVE maces
        MaceEntry entry = registry.getEntry(uid);
        if (entry == null || entry.getStatus() != MaceStatus.ACTIVE) return;

        registry.setStatus(uid, MaceStatus.DESTROYED);
        auditLogger.log(uid, "DESTROYED", "Cause: " + cause,
                null, null, location);

        if (config.isOpsOnDestroy()) {
            plugin.notifyOps("§c[MaceControl] Mace " + uid
                    + " destroyed (" + cause + ").");
        }

        if (config.isPublicOnSlotOpen()) {
            String tierName = tier != null ? tier.name() : "UNKNOWN";
            int normalCount = registry.countActiveByTier(MaceTier.LIMITED);
            int fullCount   = registry.countActiveByTier(MaceTier.FULL);
            String msg = config.getPublicSlotOpenMessage(tierName, normalCount,
                    config.getMaxNormalMaceLimit(), fullCount, config.getMaxFullMaceLimit());
            Bukkit.broadcastMessage(msg);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Checks the registry for any ACTIVE maces whose last-known location matches
     * the given block location, and marks them as DESTROYED with the given cause.
     * Used for container-destruction events (fire, explosion).
     *
     * @param loc   the location of the destroyed container block
     * @param cause the destruction cause string
     */
    private void checkRegistryForContainerDestruction(Location loc, String cause) {
        if (loc.getWorld() == null) return;
        String world = loc.getWorld().getName();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        for (MaceEntry entry : registry.getActiveEntries()) {
            if (!world.equals(entry.getLocationWorld())) continue;
            if (entry.getLocationX() != bx) continue;
            if (entry.getLocationY() != by) continue;
            if (entry.getLocationZ() != bz) continue;
            markDestroyed(entry.getUid(), cause, loc, entry.getTier());
        }
    }

    /**
     * Returns {@code true} if the given damage cause is one that destroys item entities.
     *
     * @param cause the Bukkit damage cause
     * @return {@code true} if destructive
     */
    private boolean isDestructiveCause(EntityDamageEvent.DamageCause cause) {
        return switch (cause.name()) {
            case "FIRE", "FIRE_TICK", "BURNING", "LAVA", "CACTUS", "CONTACT",
                 "VOID", "BLOCK_EXPLOSION", "ENTITY_EXPLOSION" -> true;
            default -> false;
        };
    }

    /**
     * Maps a Bukkit damage cause to a human-readable destruction cause string.
     *
     * @param cause the damage cause
     * @return a short cause string
     */
    private String mapDamageCause(EntityDamageEvent.DamageCause cause) {
        return switch (cause.name()) {
            case "FIRE", "FIRE_TICK", "BURNING" -> "FIRE";
            case "LAVA"                          -> "LAVA";
            case "CACTUS", "CONTACT"            -> "CACTUS";
            case "VOID"                          -> "VOID";
            case "BLOCK_EXPLOSION",
                 "ENTITY_EXPLOSION"             -> "EXPLOSION";
            default                              -> cause.name();
        };
    }

    /**
     * Maps a Paper {@link EntityRemoveEvent.Cause} to a human-readable destruction
     * cause string. Returns {@code null} for non-destructive removals (e.g. plugin
     * teleportation, chunk unload) that should not trigger a destruction record.
     *
     * @param cause the entity removal cause
     * @return a cause string, or {@code null} if the removal is not destructive
     */
    private String mapEntityRemoveCause(EntityRemoveEvent.Cause cause) {
        // EntityRemoveEvent.Cause values vary by Paper version.
        // We use name() comparison to stay compatible without casting to specific enum constants
        // that may not exist in all 1.21.x Paper builds.
        return switch (cause.name()) {
            case "BURNED"       -> "FIRE";
            case "DESPAWN"      -> "DESPAWN";
            case "VOID"         -> "VOID";
            case "EXPLODE"      -> "EXPLOSION";
            case "OUT_OF_WORLD" -> "VOID";
            // UNLOAD, PLUGIN, TRANSFORMATION, etc. are not destructive
            default             -> null;
        };
    }

    /**
     * Convenience method to fetch the tier of a mace from the registry.
     *
     * @param uid the mace UID
     * @return the tier, or {@code null} if the entry doesn't exist
     */
    private MaceTier getEntryTier(String uid) {
        MaceEntry entry = registry.getEntry(uid);
        return entry != null ? entry.getTier() : null;
    }
}
