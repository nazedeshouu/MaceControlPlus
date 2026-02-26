package com.macecontrol.tracking;

import com.macecontrol.MaceControl;
import com.macecontrol.data.AuditLogger;
import com.macecontrol.data.MaceEntry;
import com.macecontrol.data.MaceLocationType;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.data.MaceStatus;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Validates mace ItemStacks on every encounter and enforces the zero-tolerance
 * anti-duplication policy.
 * <p>
 * This class does NOT implement Listener. It is called by event handlers in
 * {@link RealTimeTracker} and {@link PeriodicScanner} each time a mace is
 * encountered.
 * <p>
 * Decision tree applied on each call to {@link #validateAndProcess}:
 * <ol>
 *   <li>Not a MACE — return {@code false} immediately (no-op).</li>
 *   <li>No PDC uid — UNREGISTERED → confiscate, log, alert ops.</li>
 *   <li>UID present but bad checksum — TAMPERED → confiscate, log, alert ops.</li>
 *   <li>UID valid but found in two places — DUPLICATE → confiscate the current copy.</li>
 *   <li>All checks pass → the caller may update the registry location.</li>
 * </ol>
 */
public class DupeDetector {

    private final MaceRegistry registry;
    private final MaceIdentifier identifier;
    private final AuditLogger auditLogger;
    private final MaceControl plugin;

    /**
     * Constructs a new DupeDetector.
     *
     * @param registry    the in-memory mace registry
     * @param identifier  the PDC read/write helper
     * @param auditLogger the audit-logging service
     * @param plugin      the plugin instance (used for ops notifications)
     */
    public DupeDetector(MaceRegistry registry, MaceIdentifier identifier,
                        AuditLogger auditLogger, MaceControl plugin) {
        this.registry    = registry;
        this.identifier  = identifier;
        this.auditLogger = auditLogger;
        this.plugin      = plugin;
    }

    // =========================================================================
    // Primary validation entry point
    // =========================================================================

    /**
     * Validates a mace ItemStack found during any event or scan.
     * <p>
     * Returns {@code true} if the item was confiscated and the caller should
     * treat the event as if no mace was present (e.g. cancel the event or skip
     * the location update). Returns {@code false} if the mace is legitimate and
     * the caller should proceed normally (update registry location, etc.).
     *
     * @param item          the ItemStack to validate (must already be confirmed as MACE)
     * @param player        the player context, or {@code null} if none
     * @param foundLocation the world location where the mace was found
     * @param inventory     the inventory the item lives in, or {@code null}
     * @param entity        the dropped-item entity, or {@code null}
     * @return {@code true} if confiscated; {@code false} if legitimate
     */
    public boolean validateAndProcess(ItemStack item,
                                      @Nullable Player player,
                                      Location foundLocation,
                                      @Nullable Inventory inventory,
                                      @Nullable Item entity) {
        if (item == null || item.getType() != Material.MACE) return false;

        // --- Step 1: unregistered check ---
        if (identifier.isUnregistered(item)) {
            confiscateUnregistered(item, player, inventory, entity);
            return true;
        }

        // --- Step 2: tamper check ---
        if (identifier.isTampered(item)) {
            String uid = identifier.getUid(item);
            confiscateTampered(item, uid, player, inventory, entity);
            return true;
        }

        // --- Step 3: duplicate check ---
        String uid = identifier.getUid(item);
        UUID finderUuid = player != null ? player.getUniqueId() : null;
        if (uid != null && isDuplicate(uid, foundLocation, finderUuid)) {
            confiscateDuplicate(item, uid, player, inventory, entity);
            return true;
        }

        // --- All checks passed ---
        return false;
    }

    // =========================================================================
    // Duplicate detection
    // =========================================================================

    /**
     * Determines whether the given UID is a duplicate — i.e. the same UID has
     * already been seen in a different location during this encounter.
     * <p>
     * The registry entry is the authoritative "real" copy; any newly encountered
     * copy at a different location is the duplicate and should be confiscated.
     * <p>
     * For player-held items ({@link MaceLocationType#PLAYER_INVENTORY},
     * {@link MaceLocationType#PLAYER_ENDERCHEST}, {@link MaceLocationType#OFFLINE_PLAYER}),
     * identity is established via the stored holder UUID rather than block coordinates,
     * because players move and their inventory location is always their current position.
     *
     * @param uid        the mace UID to check
     * @param foundAt    the location where this copy was found
     * @param finderUuid the UUID of the player who currently holds the item, or {@code null}
     * @return {@code true} if this is a duplicate copy that should be confiscated
     */
    public boolean isDuplicate(String uid, Location foundAt, @Nullable UUID finderUuid) {
        MaceEntry entry = registry.getEntry(uid);
        if (entry == null) {
            // Not in registry at all — treat as unregistered (caller should never reach here)
            return false;
        }
        if (entry.getStatus() == MaceStatus.DESTROYED || entry.getStatus() == MaceStatus.REVOKED) {
            // Item supposedly destroyed/revoked but physically present — treat as dupe
            return true;
        }

        MaceLocationType entryType = entry.getLocationType();

        // For player-held items: use UUID matching instead of coordinates.
        // Players move constantly so stored XYZ is only valid at the instant it was recorded.
        if (entryType == MaceLocationType.PLAYER_INVENTORY
                || entryType == MaceLocationType.PLAYER_ENDERCHEST
                || entryType == MaceLocationType.OFFLINE_PLAYER) {
            UUID storedUuid = entry.getLocationHolderUuid();
            if (storedUuid != null && finderUuid != null) {
                // Same player currently holds it — definitely not a duplicate
                if (storedUuid.equals(finderUuid)) return false;
                // Different player has an item claiming the same UID — duplicate
                return true;
            }
            // UUID unavailable on one or both sides — fall back to world sanity check only
            if (entry.getLocationWorld() != null && foundAt != null && foundAt.getWorld() != null) {
                return !entry.getLocationWorld().equals(foundAt.getWorld().getName());
            }
            // Cannot determine — be lenient rather than destroying a legitimate mace
            return false;
        }

        // For non-player-held items (ground entities, containers, item frames):
        // Do NOT use coordinate comparison. Items on the ground move due to physics/gravity
        // between the drop event and the pickup event, so the stored coordinates are
        // routinely stale. Containers are regularly fed by hoppers and dispensers between
        // registry updates. A location mismatch is therefore normal event-tracking lag,
        // not evidence of duplication. Accept any encounter and let the caller update the
        // registry to the new location.
        return false;
    }

    // =========================================================================
    // Confiscation methods
    // =========================================================================

    /**
     * Removes an unregistered mace (no PDC uid) from whatever context it appears in.
     *
     * @param item      the ItemStack to remove
     * @param holder    the holding player, or {@code null}
     * @param inventory the inventory containing the item, or {@code null}
     * @param entity    the dropped-item entity, or {@code null}
     */
    public void confiscateUnregistered(ItemStack item,
                                       @Nullable Player holder,
                                       @Nullable Inventory inventory,
                                       @Nullable Item entity) {
        removeItem(item, inventory, entity);
        String detail = buildHolderDetail(holder) + " [no PDC uid]";
        auditLogger.log("UNKNOWN", "UNREGISTERED_CONFISCATED", detail);
        plugin.notifyOps("§c[MaceControl] Unregistered mace deleted"
                + (holder != null ? " from " + holder.getName() : "") + ".");
    }

    /**
     * Removes a tampered mace (UID present but checksum invalid) from whatever
     * context it appears in.
     *
     * @param item      the ItemStack to remove
     * @param uid       the UID string read from the PDC (may be forged)
     * @param holder    the holding player, or {@code null}
     * @param inventory the inventory containing the item, or {@code null}
     * @param entity    the dropped-item entity, or {@code null}
     */
    public void confiscateTampered(ItemStack item,
                                   String uid,
                                   @Nullable Player holder,
                                   @Nullable Inventory inventory,
                                   @Nullable Item entity) {
        removeItem(item, inventory, entity);
        String detail = buildHolderDetail(holder) + " [bad checksum]";
        auditLogger.log(uid != null ? uid : "UNKNOWN", "TAMPERED_CONFISCATED", detail);
        plugin.notifyOps("§c[MaceControl] Tampered mace (uid=" + uid + ") deleted"
                + (holder != null ? " from " + holder.getName() : "") + ".");
    }

    /**
     * Removes a duplicate mace (same UID found in a second location) from whatever
     * context it appears in.
     *
     * @param item      the ItemStack to remove
     * @param uid       the duplicate UID
     * @param holder    the holding player, or {@code null}
     * @param inventory the inventory containing the item, or {@code null}
     * @param entity    the dropped-item entity, or {@code null}
     */
    public void confiscateDuplicate(ItemStack item,
                                    String uid,
                                    @Nullable Player holder,
                                    @Nullable Inventory inventory,
                                    @Nullable Item entity) {
        removeItem(item, inventory, entity);
        String detail = buildHolderDetail(holder) + " [duplicate of " + uid + "]";
        auditLogger.log(uid, "DUPE_CONFISCATED", detail);
        plugin.notifyOps("§c[MaceControl] Duplicate mace " + uid + " confiscated"
                + (holder != null ? " from " + holder.getName() : "") + ".");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Physically removes the item from whichever container is available.
     * Priority: dropped-item entity → inventory → (give up silently).
     */
    private void removeItem(ItemStack item, @Nullable Inventory inventory, @Nullable Item entity) {
        if (entity != null) {
            entity.remove();
            return;
        }
        if (inventory != null) {
            // Remove by reference — iterate and null out matching slot
            for (int i = 0; i < inventory.getSize(); i++) {
                ItemStack slot = inventory.getItem(i);
                if (slot != null && slot.equals(item)) {
                    inventory.setItem(i, null);
                    return;
                }
            }
            // Fallback: remove by type if reference match failed (copies differ)
            inventory.removeItem(item);
        }
    }

    /**
     * Builds a human-readable holder description for audit log detail strings.
     */
    private String buildHolderDetail(@Nullable Player holder) {
        return holder != null ? "Holder: " + holder.getName() : "No player context";
    }

    /**
     * Checks whether the entry's recorded location matches the given Bukkit location.
     * Coordinates are compared at block precision.
     */
    private boolean locationsMatch(MaceEntry entry, Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (entry.getLocationWorld() == null) return false;
        return entry.getLocationWorld().equals(loc.getWorld().getName())
                && entry.getLocationX() == loc.getBlockX()
                && entry.getLocationY() == loc.getBlockY()
                && entry.getLocationZ() == loc.getBlockZ();
    }
}
