package com.macecontrol.tracking;

import com.macecontrol.MaceControl;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.AuditLogger;
import com.macecontrol.data.MaceEntry;
import com.macecontrol.data.MaceLocationType;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.data.MaceStatus;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.List;
import java.util.UUID;

/**
 * Real-time event listener that tracks mace movements across every Bukkit event
 * that can change a mace's location.
 * <p>
 * Every handler follows a strict pattern:
 * <ol>
 *   <li>Extract the relevant {@link ItemStack}.</li>
 *   <li>Guard: {@code item.getType() == Material.MACE}.</li>
 *   <li>Call {@link DupeDetector#validateAndProcess} — if it returns {@code true}
 *       the item was confiscated; stop processing.</li>
 *   <li>If valid: update registry location, write audit log entry.</li>
 * </ol>
 */
public class RealTimeTracker implements Listener {

    private final MaceControl plugin;
    private final MaceConfig config;
    private final MaceRegistry registry;
    private final MaceIdentifier identifier;
    private final DupeDetector dupeDetector;
    private final AuditLogger auditLogger;

    /**
     * Constructs a new RealTimeTracker.
     *
     * @param plugin       the plugin instance
     * @param config       the plugin configuration
     * @param registry     the in-memory mace registry
     * @param identifier   the PDC read/write helper
     * @param dupeDetector the anti-duplication enforcer
     * @param auditLogger  the audit-logging service
     */
    public RealTimeTracker(MaceControl plugin, MaceConfig config, MaceRegistry registry,
                           MaceIdentifier identifier, DupeDetector dupeDetector,
                           AuditLogger auditLogger) {
        this.plugin       = plugin;
        this.config       = config;
        this.registry     = registry;
        this.identifier   = identifier;
        this.dupeDetector = dupeDetector;
        this.auditLogger  = auditLogger;
    }

    // =========================================================================
    // Inventory Events
    // =========================================================================

    /**
     * Tracks mace movement when a player clicks inside an inventory.
     * Handles all standard click types: left/right click, shift-click, number key,
     * drop, control-drop, and double-click.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // We need to determine which item moved and where it ended up.
        // The current item is the item in the clicked slot; cursor is what the player is holding.
        ItemStack cursor  = event.getCursor();
        ItemStack current = event.getCurrentItem();

        // Check cursor item (player just placed from cursor into a slot)
        if (cursor != null && cursor.getType() == Material.MACE) {
            // Destination is the clicked inventory (where the cursor item lands)
            handleInventoryMaceMove(cursor, player, event.getInventory(), event.getClickedInventory(), null);
        }

        // Check clicked slot item (player just interacted with the item in a slot)
        if (current != null && current.getType() == Material.MACE) {
            InventoryAction action = event.getAction();
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                // Shift-click: item moves to the OTHER inventory, not the clicked one.
                Inventory dest = (event.getClickedInventory() == event.getView().getTopInventory())
                        ? event.getView().getBottomInventory()
                        : event.getView().getTopInventory();
                handleInventoryMaceMove(current, player, event.getClickedInventory(), dest, null);
            } else if (isPickupToCursorAction(action)) {
                // Item goes to the player's cursor (i.e. player possession).
                // Use player inventory as destination so it gets tracked as PLAYER_INVENTORY
                // with UUID, which is immune to position-drift false dupes.
                handleInventoryMaceMove(current, player, event.getClickedInventory(), player.getInventory(), null);
            } else {
                // Other actions (swap, collect, etc.): fall through to default handling.
                handleInventoryMaceMove(current, player, event.getInventory(), event.getClickedInventory(), null);
            }
        }

        // Handle hotbar swap (NUMBER_KEY) — swapped item may be a mace
        // Note: DROP/CONTROL_DROP (Q-key) is intentionally NOT handled here.
        // PlayerDropItemEvent fires for those and is the sole handler, avoiding
        // a race condition where this click handler would set GROUND_ENTITY with
        // player.getLocation() before PlayerDropItemEvent fires with the actual
        // item entity position — a coordinate mismatch that could trigger false
        // duplicate detection.
        if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (hotbar != null && hotbar.getType() == Material.MACE) {
                handleInventoryMaceMove(hotbar, player, null, event.getClickedInventory(), null);
            }
        }
    }

    /**
     * Tracks mace movement when a player drags an item across multiple inventory slots.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack dragged = event.getOldCursor();
        if (dragged == null || dragged.getType() != Material.MACE) return;

        // Determine destination inventory based on the first raw slot involved
        if (event.getRawSlots().isEmpty()) return;
        int firstRawSlot = event.getRawSlots().iterator().next();
        Inventory dest = firstRawSlot < event.getView().getTopInventory().getSize()
                ? event.getView().getTopInventory()
                : event.getView().getBottomInventory();

        if (dupeDetector.validateAndProcess(dragged, player, player.getLocation(), dest, null)) return;

        String uid = identifier.getUid(dragged);
        if (uid == null) return;

        MaceLocationType locType = classifyInventory(dest, player);
        String world = player.getWorld().getName();
        Location loc = resolveInventoryLocation(dest, player);

        updateLocation(uid, locType, loc, player.getUniqueId(), player.getName(),
                classifyContainerType(dest));
        auditLogger.log(uid, "CONTAINER_PLACE", "Drag to " + locType.name(),
                player.getUniqueId(), player.getName(), loc);
    }

    /**
     * Tracks mace movement when an automation device (hopper, dropper, etc.)
     * moves an item between containers.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() != Material.MACE) return;

        // Validate without player or entity context
        if (dupeDetector.validateAndProcess(item, null,
                resolveInventoryLocation(event.getDestination(), null),
                event.getDestination(), null)) return;

        String uid = identifier.getUid(item);
        if (uid == null) return;

        Inventory dest = event.getDestination();
        Location destLoc = resolveInventoryLocation(dest, null);
        MaceLocationType locType = classifyContainerByHolder(dest.getHolder());
        String containerType = classifyContainerType(dest);

        updateLocation(uid, locType, destLoc, null, null, containerType);
        auditLogger.log(uid, "CONTAINER_PLACE",
                "Automation move to " + (destLoc != null ? locLString(destLoc) : "unknown"));
    }

    /**
     * Consistency catch-all: scans a real container's full contents when a player closes it,
     * updating registry locations for all maces found.
     * <p>
     * Workstation GUIs (anvil, crafting table, grindstone, etc.) are intentionally skipped —
     * maces in those inventories are already tracked by {@link #onInventoryClick} and
     * {@link #onInventoryMoveItem}, and their result slots contain virtual preview items
     * that would trigger false-positive unregistered/duplicate detections.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory inv = event.getInventory();

        // Only scan non-player inventories (chests, barrels, etc.)
        if (inv.getType() == InventoryType.PLAYER || inv.getType() == InventoryType.CREATIVE) return;

        // Skip workstation GUIs — maces inside them are already tracked by InventoryClickEvent
        // and InventoryMoveItemEvent. Scanning workstations here causes two false positives:
        //   1. Crafting table result slot shows a vanilla (unregistered) mace preview when
        //      the player has a mace recipe ready, triggering a spurious "Unregistered mace deleted".
        //   2. Anvil/grindstone/smithing result slots hold a copy of the tracked mace with the
        //      same UID as the input slot, which can trigger a spurious "Duplicate mace confiscated".
        switch (inv.getType()) {
            case ANVIL, WORKBENCH, GRINDSTONE, SMITHING, ENCHANTING, STONECUTTER,
                 CARTOGRAPHY, LOOM, BLAST_FURNACE, SMOKER, FURNACE -> { return; }
            default -> { /* fall through to scan real containers */ }
        }

        MaceLocationType locType = classifyInventory(inv, player);
        Location invLoc = resolveInventoryLocation(inv, player);
        String containerType = classifyContainerType(inv);

        // For player-associated location types, preserve the holder UUID so that
        // isDuplicate can use UUID matching instead of falling back to the weaker
        // world-only sanity check. Without this, closing an ender chest would
        // overwrite the stored UUID with null, weakening dupe detection.
        boolean isPlayerLocation = (locType == MaceLocationType.PLAYER_INVENTORY
                || locType == MaceLocationType.PLAYER_ENDERCHEST);

        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType() != Material.MACE) continue;
            if (dupeDetector.validateAndProcess(item, player, invLoc, inv, null)) continue;
            String uid = identifier.getUid(item);
            if (uid == null) continue;
            updateLocation(uid, locType, invLoc,
                    isPlayerLocation ? player.getUniqueId() : null,
                    isPlayerLocation ? player.getName() : null,
                    containerType);
        }
    }

    // =========================================================================
    // Player Events
    // =========================================================================

    /**
     * Tracks a player dropping a mace onto the ground.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Item droppedEntity = event.getItemDrop();
        ItemStack item = droppedEntity.getItemStack();
        if (item.getType() != Material.MACE) return;

        Player player = event.getPlayer();
        if (dupeDetector.validateAndProcess(item, player, droppedEntity.getLocation(),
                null, droppedEntity)) return;

        String uid = identifier.getUid(item);
        if (uid == null) return;

        updateLocation(uid, MaceLocationType.GROUND_ENTITY,
                droppedEntity.getLocation(), player.getUniqueId(), player.getName(), null);
        auditLogger.log(uid, "DROP", "Player dropped",
                player.getUniqueId(), player.getName(), droppedEntity.getLocation());
    }

    /**
     * Tracks an entity (usually a player) picking up a mace item entity.
     * <p>
     * Runs at HIGH priority (not MONITOR) so we can cancel the event before Bukkit
     * transfers the item to the entity's inventory. At MONITOR the transfer has already
     * happened and cancellation has no effect, meaning a confiscated item would still
     * end up in the picker's inventory.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        Item droppedItem = event.getItem();
        ItemStack item = droppedItem.getItemStack();
        if (item.getType() != Material.MACE) return;

        Entity picker = event.getEntity();
        // Use the item entity's own location for duplicate validation — the stored registry
        // location is where the item was last seen (on the ground), not where the player is.
        Location itemLoc   = droppedItem.getLocation();
        Location playerLoc = picker.getLocation();

        if (picker instanceof Player player) {
            if (dupeDetector.validateAndProcess(item, player, itemLoc,
                    player.getInventory(), droppedItem)) {
                event.setCancelled(true); // prevent item entering inventory
                return;
            }
            String uid = identifier.getUid(item);
            if (uid == null) return;
            updateLocation(uid, MaceLocationType.PLAYER_INVENTORY,
                    playerLoc, player.getUniqueId(), player.getName(), null);
            auditLogger.log(uid, "PICKUP", "Player picked up",
                    player.getUniqueId(), player.getName(), playerLoc);
        } else {
            // Non-player entity picked up mace (rare, but handle it)
            if (dupeDetector.validateAndProcess(item, null, itemLoc, null, droppedItem)) {
                event.setCancelled(true);
                return;
            }
            String uid = identifier.getUid(item);
            if (uid == null) return;
            updateLocation(uid, MaceLocationType.UNKNOWN, playerLoc, null, null,
                    picker.getType().name());
            auditLogger.log(uid, "PICKUP", "Non-player pickup: " + picker.getType().name());
        }
    }

    /**
     * Tracks maces in a player's death drops and updates their location to GROUND_ENTITY.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player deceased = event.getEntity();
        Location deathLoc = deceased.getLocation();

        for (ItemStack item : event.getDrops()) {
            if (item == null || item.getType() != Material.MACE) continue;
            if (dupeDetector.validateAndProcess(item, deceased, deathLoc, null, null)) continue;
            String uid = identifier.getUid(item);
            if (uid == null) continue;
            updateLocation(uid, MaceLocationType.GROUND_ENTITY,
                    deathLoc, deceased.getUniqueId(), deceased.getName(), null);
            auditLogger.log(uid, "DEATH_DROP",
                    "Player died with mace",
                    deceased.getUniqueId(), deceased.getName(), deathLoc);
        }
    }

    /**
     * Scans a player's full inventory and ender chest on login, validates all maces,
     * and processes any queued revocations.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();

        // --- Scan main inventory ---
        scanAndUpdateInventory(player.getInventory(), MaceLocationType.PLAYER_INVENTORY,
                player, loc, "main inventory");

        // --- Scan ender chest ---
        scanAndUpdateInventory(player.getEnderChest(), MaceLocationType.PLAYER_ENDERCHEST,
                player, loc, "ender chest");

        // --- Cursor item ---
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() == Material.MACE) {
            if (!dupeDetector.validateAndProcess(cursor, player, loc, null, null)) {
                String uid = identifier.getUid(cursor);
                if (uid != null) {
                    updateLocation(uid, MaceLocationType.PLAYER_INVENTORY,
                            loc, player.getUniqueId(), player.getName(), null);
                }
            }
        }

        // --- Process queued revocations ---
        List<String> revokedUids = registry.popQueuedRevocations(player.getUniqueId());
        for (String uid : revokedUids) {
            removeFromInventory(player, uid);
            player.sendMessage("§c[MaceControl] Mace " + uid
                    + " has been revoked and removed from your inventory.");
            auditLogger.log(uid, "REVOKED",
                    "Queued revocation applied on login for " + player.getName(),
                    player.getUniqueId(), player.getName(), loc);
        }
    }

    /**
     * Updates registry location to OFFLINE_PLAYER for all maces in a player's
     * inventory and ender chest when they disconnect.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.MACE) continue;
            String uid = identifier.getUid(item);
            if (uid == null) continue;
            updateLocation(uid, MaceLocationType.OFFLINE_PLAYER,
                    player.getLocation(), player.getUniqueId(), player.getName(), null);
        }

        for (ItemStack item : player.getEnderChest().getContents()) {
            if (item == null || item.getType() != Material.MACE) continue;
            String uid = identifier.getUid(item);
            if (uid == null) continue;
            updateLocation(uid, MaceLocationType.OFFLINE_PLAYER,
                    player.getLocation(), player.getUniqueId(), player.getName(), null);
        }
    }

    // =========================================================================
    // Entity / Block Events
    // =========================================================================

    /**
     * Tracks a mace being ejected from a dispenser as a ground entity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() != Material.MACE) return;

        Location dispenseLoc = event.getBlock().getLocation();
        if (dupeDetector.validateAndProcess(item, null, dispenseLoc, null, null)) return;

        String uid = identifier.getUid(item);
        if (uid == null) return;

        updateLocation(uid, MaceLocationType.GROUND_ENTITY, dispenseLoc, null, null, "DISPENSER");
        auditLogger.log(uid, "DROP", "Dispensed from " + locLString(dispenseLoc));
    }

    /**
     * Tracks a mace being placed into an item frame by a player.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (!(target instanceof ItemFrame frame)) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItem(event.getHand());
        if (held == null || held.getType() != Material.MACE) return;

        Location frameLoc = frame.getLocation();
        if (dupeDetector.validateAndProcess(held, player, frameLoc,
                player.getInventory(), null)) return;

        String uid = identifier.getUid(held);
        if (uid == null) return;

        // Schedule 1-tick delay to let the frame actually receive the item
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            ItemStack framed = frame.getItem();
            if (framed != null && framed.getType() == Material.MACE) {
                updateLocation(uid, MaceLocationType.ITEM_FRAME,
                        frameLoc, player.getUniqueId(), player.getName(), "ITEM_FRAME");
                auditLogger.log(uid, "CONTAINER_PLACE", "Placed in item frame at "
                        + locLString(frameLoc), player.getUniqueId(), player.getName(), frameLoc);
            }
        }, 1L);
    }

    /**
     * Handles an item frame breaking — updates any held mace to GROUND_ENTITY.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        ItemStack held = frame.getItem();
        if (held == null || held.getType() != Material.MACE) return;

        String uid = identifier.getUid(held);
        if (uid == null) return;

        Location loc = frame.getLocation();
        updateLocation(uid, MaceLocationType.GROUND_ENTITY, loc, null, null, null);
        auditLogger.log(uid, "DROP", "Item frame broken at " + locLString(loc));
    }

    /**
     * Logs container destruction when a block that is an {@link InventoryHolder} is broken,
     * and updates mace registry records for maces last known at that location.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof InventoryHolder holder)) return;

        Location blockLoc = block.getLocation();
        String world = block.getWorld().getName();

        for (MaceEntry entry : registry.getActiveEntries()) {
            if (!world.equals(entry.getLocationWorld())) continue;
            if (entry.getLocationX() != blockLoc.getBlockX()) continue;
            if (entry.getLocationY() != blockLoc.getBlockY()) continue;
            if (entry.getLocationZ() != blockLoc.getBlockZ()) continue;

            // Mace was registered as being in this container
            auditLogger.log(entry.getUid(), "CONTAINER_BROKEN",
                    "Container broken by " + event.getPlayer().getName()
                    + " at " + locLString(blockLoc),
                    event.getPlayer().getUniqueId(), event.getPlayer().getName(), blockLoc);

            // Location becomes unknown — it will drop as item entity; let pickup event handle it
            updateLocation(entry.getUid(), MaceLocationType.GROUND_ENTITY,
                    blockLoc, null, null, null);
        }
    }

    /**
     * Prevents mace items from merging into stacks (maces must always be individual items).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemMerge(ItemMergeEvent event) {
        if (event.getEntity().getItemStack().getType() == Material.MACE
                || event.getTarget().getItemStack().getType() == Material.MACE) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Scans all ItemStacks in the given inventory, validates each mace, and updates
     * its registry location.
     *
     * @param inventory     the inventory to scan
     * @param locationType  the location type to record for found maces
     * @param player        the player context (used for holder UUID/name and validation)
     * @param loc           the physical location to record
     * @param contextName   a human-readable name for audit log detail strings
     */
    private void scanAndUpdateInventory(Inventory inventory, MaceLocationType locationType,
                                        Player player, Location loc, String contextName) {
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;

            // Check shulker boxes
            if (isShulkerBox(item)) {
                scanShulkerBox(item, player, loc, locationType);
                continue;
            }

            // Check bundles
            if (item.getType() == Material.BUNDLE) {
                scanBundle(item, player, loc, locationType);
                continue;
            }

            if (item.getType() != Material.MACE) continue;

            if (dupeDetector.validateAndProcess(item, player, loc, inventory, null)) continue;
            String uid = identifier.getUid(item);
            if (uid == null) continue;

            updateLocation(uid, locationType, loc, player.getUniqueId(), player.getName(), null);
            auditLogger.log(uid, "SCAN_VERIFIED",
                    "Found in " + contextName + " for " + player.getName(),
                    player.getUniqueId(), player.getName(), loc);
        }
    }

    /**
     * Scans the inventory of a shulker box item for maces.
     */
    private void scanShulkerBox(ItemStack shulkerItem, @org.jetbrains.annotations.Nullable Player player,
                                 Location loc, MaceLocationType parentLocationType) {
        if (!(shulkerItem.getItemMeta() instanceof BlockStateMeta bsm)) return;
        if (!(bsm.getBlockState() instanceof InventoryHolder ih)) return;
        Inventory inner = ih.getInventory();
        for (ItemStack inner_item : inner.getContents()) {
            if (inner_item == null || inner_item.getType() != Material.MACE) continue;
            if (dupeDetector.validateAndProcess(inner_item, player, loc, inner, null)) continue;
            String uid = identifier.getUid(inner_item);
            if (uid == null) continue;
            updateLocation(uid, MaceLocationType.SHULKER_ITEM, loc,
                    player != null ? player.getUniqueId() : null,
                    player != null ? player.getName() : null,
                    "SHULKER_BOX");
            auditLogger.log(uid, "SCAN_VERIFIED", "Found inside shulker box");
        }
    }

    /**
     * Scans bundle contents for maces.
     */
    private void scanBundle(ItemStack bundleItem, @org.jetbrains.annotations.Nullable Player player,
                             Location loc, MaceLocationType parentLocationType) {
        if (!(bundleItem.getItemMeta() instanceof BundleMeta bundleMeta)) return;
        for (ItemStack inner : bundleMeta.getItems()) {
            if (inner == null || inner.getType() != Material.MACE) continue;
            if (dupeDetector.validateAndProcess(inner, player, loc, null, null)) continue;
            String uid = identifier.getUid(inner);
            if (uid == null) continue;
            updateLocation(uid, MaceLocationType.BUNDLE, loc,
                    player != null ? player.getUniqueId() : null,
                    player != null ? player.getName() : null,
                    "BUNDLE");
            auditLogger.log(uid, "SCAN_VERIFIED", "Found inside bundle");
        }
    }

    /**
     * Handles the common case of a mace moving within or between inventories.
     * Determines the destination location type and updates the registry.
     */
    private void handleInventoryMaceMove(ItemStack item, Player player,
                                         @org.jetbrains.annotations.Nullable Inventory from,
                                         @org.jetbrains.annotations.Nullable Inventory to,
                                         @org.jetbrains.annotations.Nullable Item entity) {
        Location playerLoc = player.getLocation();
        // Use the source inventory's actual location for the duplicate check.
        // This makes the stored XYZ match for CONTAINER-type entries (chests, anvils, etc.)
        // instead of comparing against the player's current standing position.
        // Falls back to player location for cursor items and null-holder inventories.
        Location validLoc = resolveInventoryLocation(from, player);
        if (validLoc == null) validLoc = playerLoc;

        Inventory targetInv = to != null ? to : from;
        if (dupeDetector.validateAndProcess(item, player, validLoc, targetInv, entity)) return;

        String uid = identifier.getUid(item);
        if (uid == null) return;

        MaceLocationType locType = to != null ? classifyInventory(to, player)
                : MaceLocationType.PLAYER_INVENTORY;
        Location invLoc = to != null ? resolveInventoryLocation(to, player) : playerLoc;
        String containerType = to != null ? classifyContainerType(to) : null;

        updateLocation(uid, locType, invLoc, player.getUniqueId(), player.getName(), containerType);

        String eventType = (locType == MaceLocationType.PLAYER_INVENTORY
                || locType == MaceLocationType.PLAYER_ENDERCHEST)
                ? "PICKUP" : "CONTAINER_PLACE";
        auditLogger.log(uid, eventType, "Moved to " + locType.name(),
                player.getUniqueId(), player.getName(), invLoc);
    }

    /**
     * Removes all maces with the given UID from a player's inventory (used for revocations).
     */
    private void removeFromInventory(Player player, String uid) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != Material.MACE) continue;
            if (uid.equals(identifier.getUid(item))) {
                player.getInventory().setItem(i, null);
            }
        }
        // Also check ender chest
        for (int i = 0; i < player.getEnderChest().getSize(); i++) {
            ItemStack item = player.getEnderChest().getItem(i);
            if (item == null || item.getType() != Material.MACE) continue;
            if (uid.equals(identifier.getUid(item))) {
                player.getEnderChest().setItem(i, null);
            }
        }
    }

    /**
     * Updates the registry for a mace's location.
     */
    private void updateLocation(String uid, MaceLocationType locType, Location loc,
                                 @org.jetbrains.annotations.Nullable UUID holderUuid,
                                 @org.jetbrains.annotations.Nullable String holderName,
                                 @org.jetbrains.annotations.Nullable String containerType) {
        if (loc == null || loc.getWorld() == null) return;
        registry.updateLocation(uid, locType,
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                holderUuid, holderName, containerType);
    }

    /**
     * Classifies an inventory's location type based on its holder and type.
     * <p>
     * Temporary interactive workstations (anvil, crafting table, grindstone, etc.) are
     * classified as {@link MaceLocationType#PLAYER_INVENTORY} so the mace is tracked by
     * player UUID rather than block coordinates. This prevents false duplicate detection
     * when the player moves while using a workstation — since the item is effectively
     * "in the player's possession" during the interaction session.
     */
    private MaceLocationType classifyInventory(Inventory inv, @org.jetbrains.annotations.Nullable Player player) {
        if (inv.getHolder() instanceof Player) return MaceLocationType.PLAYER_INVENTORY;
        if (inv.getType() == InventoryType.ENDER_CHEST) return MaceLocationType.PLAYER_ENDERCHEST;
        // Treat temporary workstations as player-held (UUID-matched, not XYZ-matched).
        switch (inv.getType()) {
            case ANVIL, WORKBENCH, GRINDSTONE, SMITHING, ENCHANTING, STONECUTTER,
                 CARTOGRAPHY, LOOM, BLAST_FURNACE, SMOKER, FURNACE ->
                    { return MaceLocationType.PLAYER_INVENTORY; }
            default -> { /* fall through */ }
        }
        return classifyContainerByHolder(inv.getHolder());
    }

    /**
     * Classifies a location type based on the inventory holder's type.
     */
    private MaceLocationType classifyContainerByHolder(@org.jetbrains.annotations.Nullable InventoryHolder holder) {
        if (holder == null) return MaceLocationType.CONTAINER;
        String name = holder.getClass().getSimpleName().toUpperCase();
        if (name.contains("HOPPER"))     return MaceLocationType.HOPPER;
        if (name.contains("DROPPER"))    return MaceLocationType.DROPPER;
        if (name.contains("DISPENSER")) return MaceLocationType.DISPENSER;
        if (name.contains("MINECART")) {
            if (name.contains("HOPPER")) return MaceLocationType.MINECART_HOPPER;
            return MaceLocationType.MINECART_CHEST;
        }
        return MaceLocationType.CONTAINER;
    }

    /**
     * Returns a container-type string suitable for audit/registry storage.
     */
    private @org.jetbrains.annotations.Nullable String classifyContainerType(Inventory inv) {
        if (inv == null) return null;
        return inv.getType().name();
    }

    /**
     * Resolves the world location of an inventory (block-based containers have a block location;
     * player inventories use the player's location).
     */
    private @org.jetbrains.annotations.Nullable Location resolveInventoryLocation(
            Inventory inv, @org.jetbrains.annotations.Nullable Player player) {
        if (inv == null) return player != null ? player.getLocation() : null;

        InventoryHolder holder = inv.getHolder();
        if (holder instanceof DoubleChest dc) {
            // Use the left-side chest location for double chests
            if (dc.getLeftSide() instanceof InventoryHolder left) {
                if (left instanceof org.bukkit.block.Chest chest) {
                    return chest.getLocation();
                }
            }
        }
        if (holder instanceof org.bukkit.block.BlockState bs) {
            return bs.getLocation();
        }
        if (holder instanceof Player p) return p.getLocation();
        if (holder instanceof org.bukkit.entity.Entity e) return e.getLocation();
        return player != null ? player.getLocation() : null;
    }

    /**
     * Returns {@code true} if the given {@link InventoryAction} represents the slot item
     * moving to the player's cursor (i.e. the player is picking the item up).
     * These actions mean the item leaves its current slot and enters the player's possession.
     */
    private boolean isPickupToCursorAction(InventoryAction action) {
        return action == InventoryAction.PICKUP_ALL
                || action == InventoryAction.PICKUP_HALF
                || action == InventoryAction.PICKUP_SOME
                || action == InventoryAction.PICKUP_ONE
                || action == InventoryAction.SWAP_WITH_CURSOR; // slot item goes to cursor
    }

    /**
     * Returns {@code true} if the given item is a shulker box of any color.
     */
    private boolean isShulkerBox(ItemStack item) {
        if (item == null) return false;
        return item.getType().name().endsWith("_SHULKER_BOX");
    }

    /**
     * Formats a location to a short string for audit detail messages.
     */
    private String locLString(Location loc) {
        if (loc == null) return "null";
        return (loc.getWorld() != null ? loc.getWorld().getName() : "?")
                + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
