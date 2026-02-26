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
        if (uid != null && isDuplicate(uid, foundLocation)) {
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
     *
     * @param uid     the mace UID to check
     * @param foundAt the location where this copy was found
     * @return {@code true} if this is a duplicate copy that should be confiscated
     */
    public boolean isDuplicate(String uid, Location foundAt) {
        MaceEntry entry = registry.getEntry(uid);
        if (entry == null) {
            // Not in registry at all — treat as unregistered (caller should never reach here)
            return false;
        }
        if (entry.getStatus() == MaceStatus.DESTROYED || entry.getStatus() == MaceStatus.REVOKED) {
            // Item supposedly destroyed/revoked but physically present — treat as dupe
            return true;
        }
        // If the entry's last-known location matches foundAt, not a dupe
        if (locationsMatch(entry, foundAt)) {
            return false;
        }
        // Different location — could be a real move not yet recorded, or a dupe.
        // We conservatively treat it as a dupe only if it was RECENTLY verified at
        // a different location. If locationUpdatedAt is very old the mace may simply
        // have moved without an event firing (e.g. hopper edge case).
        // For safety: if the registry entry has ANY recorded active location that
        // differs from foundAt, treat as dupe.
        return entry.getLocationType() != null
                && entry.getLocationType() != MaceLocationType.UNKNOWN
                && entry.getLocationWorld() != null;
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
