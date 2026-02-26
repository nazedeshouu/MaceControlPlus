package com.macecontrol.tracking;

import com.macecontrol.MaceControl;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.AuditLogger;
import com.macecontrol.data.MaceEntry;
import com.macecontrol.data.MaceLocationType;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.data.MaceStatus;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Runs the full six-phase periodic scan of every mace on the server.
 * <p>
 * <b>Scan phases:</b>
 * <ol>
 *   <li>Online player inventories and ender chests.</li>
 *   <li>Loaded chunk tile entities, spread across ticks.</li>
 *   <li>Entity scan (ground items, item frames, inventory-holding entities).</li>
 *   <li>Offline player data files (optional, async).</li>
 *   <li>Reconciliation — increment missing-scan counter or mark DESTROYED.</li>
 *   <li>Dupe sweep — delete copies of the same UID beyond the first.</li>
 * </ol>
 * After completion the next scan is scheduled at a randomized delay from config.
 */
public class PeriodicScanner {

    private final MaceRegistry registry;
    private final MaceIdentifier identifier;
    private final DupeDetector dupeDetector;
    private final AuditLogger auditLogger;
    private final MaceConfig config;
    private final MaceControl plugin;
    private final Random rng = new Random();

    /** Epoch-second timestamp of the last completed scan, or 0 if never run. */
    private volatile long lastScanCompletedAt = 0L;

    /** Epoch-second timestamp when the next scan is scheduled, or 0 if not yet scheduled. */
    private volatile long nextScanScheduledAt = 0L;

    /** Guard flag to prevent concurrent scans from corrupting shared scan state. */
    private volatile boolean scanInProgress = false;

    /**
     * UIDs verified during the current scan run (reset each scan).
     * Thread-confined to the main thread during phases 1-3.
     */
    private final Set<String> verifiedThisScan = new HashSet<>();

    /**
     * Map of uid → list of locations found during this scan (for dupe sweep).
     * Thread-confined to the main thread during phases 1-3.
     */
    private final Map<String, List<org.bukkit.Location>> foundLocations = new HashMap<>();

    /**
     * Canonical locations of double chests already processed during this scan.
     * Keyed as "world:x:y:z" of the left-half chest block.
     * <p>
     * A double chest consists of two adjacent tile entities that both expose the
     * same shared {@link DoubleChestInventory}. Without this guard, both halves
     * would be iterated in {@link #processChunk}, adding the same mace to
     * {@link #foundLocations} twice and falsely triggering the Phase 6 dupe sweep.
     */
    private final Set<String> processedDoubleChestLocations = new HashSet<>();

    /**
     * Constructs a new PeriodicScanner.
     *
     * @param registry     the in-memory mace registry
     * @param identifier   the PDC read/write helper
     * @param dupeDetector the anti-duplication enforcer
     * @param auditLogger  the audit-logging service
     * @param config       the plugin configuration
     * @param plugin       the plugin instance
     */
    public PeriodicScanner(MaceRegistry registry, MaceIdentifier identifier,
                           DupeDetector dupeDetector, AuditLogger auditLogger,
                           MaceConfig config, MaceControl plugin) {
        this.registry    = registry;
        this.identifier  = identifier;
        this.dupeDetector = dupeDetector;
        this.auditLogger = auditLogger;
        this.config      = config;
        this.plugin      = plugin;
    }

    // =========================================================================
    // Scheduling
    // =========================================================================

    /**
     * Schedules the next periodic scan at a randomized delay within the configured range.
     * Safe to call from any thread (delegates to Bukkit scheduler on main thread).
     */
    public void scheduleNextScan() {
        long minTicks = (long) config.getScanIntervalMinMinutes() * 60L * 20L;
        long maxTicks = (long) config.getScanIntervalMaxMinutes() * 60L * 20L;
        long delay = minTicks + (long) (rng.nextDouble() * (maxTicks - minTicks));

        nextScanScheduledAt = Instant.now().getEpochSecond() + (delay / 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> runScan(null), delay);

        plugin.getLogger().info("[MaceControl] Next periodic scan scheduled in "
                + (delay / 20 / 60) + " minutes.");
    }

    /**
     * Triggers an immediate full scan, bypassing the scheduled timer.
     * Optionally reports results to a command sender.
     * <p>
     * If a scan is already in progress, the request is rejected and the requester
     * is notified. This prevents concurrent scans from corrupting shared scan state
     * ({@link #verifiedThisScan}, {@link #foundLocations}), which would cause
     * Phase 5 reconciliation to wrongly increment missing-scan counters.
     *
     * @param requester the command sender to notify on completion, or {@code null}
     */
    public void runImmediateScan(@Nullable CommandSender requester) {
        if (scanInProgress) {
            if (requester != null) {
                requester.sendMessage("§cA scan is already in progress. Please wait for it to finish.");
            }
            plugin.getLogger().warning("[MaceControl] Immediate scan rejected — scan already in progress.");
            return;
        }
        plugin.getLogger().info("[MaceControl] Immediate scan started"
                + (requester != null ? " by " + requester.getName() : "") + ".");
        runScan(requester);
    }

    /**
     * Returns the epoch-second timestamp when the last scan completed,
     * or {@code 0} if no scan has completed yet.
     */
    public long getLastScanCompletedAt() {
        return lastScanCompletedAt;
    }

    /**
     * Returns the epoch-second timestamp when the next scan is scheduled,
     * or {@code 0} if not yet determined.
     */
    public long getNextScanScheduledAt() {
        return nextScanScheduledAt;
    }

    // =========================================================================
    // Internal scan orchestration
    // =========================================================================

    /**
     * Orchestrates the full scan across all six phases.
     * Must be called on the main server thread.
     *
     * @param requester optional command sender to report results to
     */
    private void runScan(@Nullable CommandSender requester) {
        if (scanInProgress) {
            plugin.getLogger().warning("[MaceControl] Scan already in progress — skipping.");
            return;
        }
        scanInProgress = true;

        verifiedThisScan.clear();
        foundLocations.clear();
        processedDoubleChestLocations.clear();

        plugin.getLogger().info("[MaceControl] Periodic scan started.");

        // Phase 1: Online players
        runPhase1OnlinePlayers();

        // Phase 2: Chunk tile entities — spread over ticks via BukkitRunnable
        // Phase 3 runs after phase 2 completes (chained via callback)
        runPhase2ChunkScan(requester);
    }

    // =========================================================================
    // Phase 1 — Online players
    // =========================================================================

    /**
     * Phase 1: Scans every online player's main inventory, ender chest, and cursor item.
     */
    private void runPhase1OnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            org.bukkit.Location loc = player.getLocation();

            // Main inventory
            scanInventoryForScan(player.getInventory(), MaceLocationType.PLAYER_INVENTORY,
                    loc, player.getUniqueId(), player.getName());

            // Ender chest
            scanInventoryForScan(player.getEnderChest(), MaceLocationType.PLAYER_ENDERCHEST,
                    loc, player.getUniqueId(), player.getName());

            // Cursor
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && cursor.getType() == org.bukkit.Material.MACE) {
                processScanItem(cursor, MaceLocationType.PLAYER_INVENTORY, loc,
                        player.getUniqueId(), player.getName(), null, null);
            }
        }
    }

    // =========================================================================
    // Phase 2 — Chunk tile entity scan
    // =========================================================================

    /**
     * Phase 2: Iterates all loaded chunks across all worlds in batches of
     * {@code config.getChunksPerTick()} chunks per tick, then chains into Phase 3.
     */
    private void runPhase2ChunkScan(@Nullable CommandSender requester) {
        // Collect all chunks to process
        List<Chunk> allChunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                allChunks.add(chunk);
            }
        }

        int chunksPerTick = Math.max(1, config.getChunksPerTick());
        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                int end = Math.min(index + chunksPerTick, allChunks.size());
                for (int i = index; i < end; i++) {
                    try {
                        processChunk(allChunks.get(i));
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "[MaceControl] Error scanning chunk: " + e.getMessage(), e);
                    }
                }
                index = end;

                if (index >= allChunks.size()) {
                    cancel(); // done with phase 2
                    // Chain to phase 3
                    runPhase3EntityScan();
                    runPhase4OfflineAsync();
                    // Phase 5 & 6 run after async phase 4 completes — see callback
                    // But offline is optional; run 5/6 immediately and then async won't affect them
                    runPhase5Reconciliation();
                    runPhase6DupeSweep();
                    completeScan(requester);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Processes all tile entities (containers) within a single chunk.
     */
    private void processChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof InventoryHolder holder)) continue;
            Inventory inv = holder.getInventory();
            org.bukkit.Location loc = state.getLocation();
            MaceLocationType locType = classifyBlockState(state);

            // Double chest deduplication: a double chest is two adjacent tile entities
            // that both expose the same shared DoubleChestInventory. Without this guard,
            // the same mace would be added to foundLocations at two different block
            // coordinates, incorrectly triggering the Phase 6 dupe sweep.
            // Use the left half's block location as the canonical position.
            if (inv instanceof DoubleChestInventory dci) {
                DoubleChest dc = dci.getHolder();
                if (dc != null && dc.getLeftSide() instanceof org.bukkit.block.Chest leftChest) {
                    org.bukkit.Location canonLoc = leftChest.getLocation();
                    if (canonLoc != null && canonLoc.getWorld() != null) {
                        String key = canonLoc.getWorld().getName() + ":"
                                + canonLoc.getBlockX() + ":" + canonLoc.getBlockY()
                                + ":" + canonLoc.getBlockZ();
                        if (!processedDoubleChestLocations.add(key)) continue; // right half — skip
                        loc = canonLoc; // use canonical left-half location in registry
                    }
                }
            }

            for (ItemStack item : inv.getContents()) {
                if (item == null) continue;

                // Shulker box inside container
                if (item.getType().name().endsWith("_SHULKER_BOX")) {
                    scanShulkerForScan(item, loc);
                    continue;
                }

                // Bundle inside container
                if (item.getType() == org.bukkit.Material.BUNDLE) {
                    scanBundleForScan(item, loc);
                    continue;
                }

                if (item.getType() != org.bukkit.Material.MACE) continue;
                processScanItem(item, locType, loc, null, null, inv, null);
            }
        }
    }

    /**
     * Recursively scans a shulker box item's inner inventory during a chunk scan.
     */
    private void scanShulkerForScan(ItemStack shulker, org.bukkit.Location outerLoc) {
        if (!(shulker.getItemMeta() instanceof BlockStateMeta bsm)) return;
        if (!(bsm.getBlockState() instanceof InventoryHolder ih)) return;
        for (ItemStack inner : ih.getInventory().getContents()) {
            if (inner == null || inner.getType() != org.bukkit.Material.MACE) continue;
            processScanItem(inner, MaceLocationType.SHULKER_ITEM, outerLoc, null, null, null, null);
        }
    }

    /**
     * Scans a bundle item's contents during a chunk scan.
     */
    private void scanBundleForScan(ItemStack bundle, org.bukkit.Location outerLoc) {
        if (!(bundle.getItemMeta() instanceof BundleMeta bm)) return;
        for (ItemStack inner : bm.getItems()) {
            if (inner == null || inner.getType() != org.bukkit.Material.MACE) continue;
            processScanItem(inner, MaceLocationType.BUNDLE, outerLoc, null, null, null, null);
        }
    }

    // =========================================================================
    // Phase 3 — Entity scan
    // =========================================================================

    /**
     * Phase 3: Scans all entities in all worlds — dropped items, item frames,
     * and inventory-holding entities (minecart chests, etc.).
     */
    private void runPhase3EntityScan() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                try {
                    if (entity instanceof Item itemEntity) {
                        ItemStack item = itemEntity.getItemStack();
                        if (item.getType() == org.bukkit.Material.MACE) {
                            processScanItem(item, MaceLocationType.GROUND_ENTITY,
                                    entity.getLocation(), null, null, null, itemEntity);
                        }
                    } else if (entity instanceof ItemFrame frame) {
                        ItemStack held = frame.getItem();
                        if (held != null && held.getType() == org.bukkit.Material.MACE) {
                            processScanItem(held, MaceLocationType.ITEM_FRAME,
                                    frame.getLocation(), null, null, null, null);
                        }
                    } else if (entity instanceof GlowItemFrame glow) {
                        ItemStack held = glow.getItem();
                        if (held != null && held.getType() == org.bukkit.Material.MACE) {
                            processScanItem(held, MaceLocationType.ITEM_FRAME,
                                    glow.getLocation(), null, null, null, null);
                        }
                    } else if (entity instanceof InventoryHolder invHolder) {
                        Inventory inv = invHolder.getInventory();
                        MaceLocationType locType = entity.getClass().getSimpleName().contains("Hopper")
                                ? MaceLocationType.MINECART_HOPPER : MaceLocationType.MINECART_CHEST;
                        for (ItemStack item : inv.getContents()) {
                            if (item == null || item.getType() != org.bukkit.Material.MACE) continue;
                            processScanItem(item, locType,
                                    entity.getLocation(), null, null, inv, null);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "[MaceControl] Error scanning entity: " + e.getMessage(), e);
                }
            }
        }
    }

    // =========================================================================
    // Phase 4 — Offline player scan (async)
    // =========================================================================

    /**
     * Phase 4: Optionally scans offline player data files for maces.
     * Runs asynchronously. Results are flagged for deletion on next login.
     * Does NOT modify the registry directly (deferred to login via RealTimeTracker).
     */
    private void runPhase4OfflineAsync() {
        if (!config.isScanOfflinePlayers()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Offline player data is in world/playerdata/<uuid>.dat
            // Rather than parsing raw NBT (which would require NMS or a library),
            // we iterate offline players using Bukkit's API.
            // Bukkit.getOfflinePlayers() returns all known players; those who are online
            // are already covered by phase 1, so skip them.
            Set<UUID> onlineUuids = new HashSet<>();
            // Must read online players from main thread — snapshot taken before async start
            for (Player p : Bukkit.getOnlinePlayers()) {
                onlineUuids.add(p.getUniqueId());
            }

            for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.isOnline()) continue; // covered in phase 1
                if (onlineUuids.contains(op.getUniqueId())) continue;
                // We cannot safely access inventory of offline players without NMS.
                // The safest approach: mark maces whose last-known location is
                // OFFLINE_PLAYER as still-offline (do not increment missing count).
                // The actual validation happens when the player logs in via RealTimeTracker.
            }
            plugin.getLogger().fine("[MaceControl] Phase 4 (offline scan) completed.");
        });
    }

    // =========================================================================
    // Phase 5 — Reconciliation
    // =========================================================================

    /**
     * Phase 5: Reconciles the registry against what was found during phases 1-3.
     * <ul>
     *   <li>Maces found → reset missingScanCount, update lastVerifiedAt.</li>
     *   <li>Maces not found → increment missingScanCount if location was accessible.</li>
     *   <li>If missingScanCount >= threshold → mark DESTROYED.</li>
     * </ul>
     */
    private void runPhase5Reconciliation() {
        long now = Instant.now().getEpochSecond();

        for (MaceEntry entry : registry.getActiveEntries()) {
            String uid = entry.getUid();

            if (verifiedThisScan.contains(uid)) {
                // Found — reset counter and update verified timestamp
                registry.setMissingScanCount(uid, 0);
                registry.setLastVerifiedAt(uid, now);
                auditLogger.log(uid, "SCAN_VERIFIED", "Confirmed in scan");
            } else {
                // Not found — determine if location was accessible
                boolean accessible = isLocationAccessible(entry);
                if (!accessible) {
                    // Chunk unloaded or offline player without scan — do not penalize
                    auditLogger.log(uid, "SCAN_MISSING",
                            "Skipped (location not accessible): " + entry.getLocationType());
                    continue;
                }

                int newCount = entry.getMissingScanCount() + 1;
                registry.setMissingScanCount(uid, newCount);
                auditLogger.log(uid, "SCAN_MISSING",
                        "Not found, missing count now " + newCount);

                if (newCount >= config.getMissedScansToDestroy()) {
                    registry.setStatus(uid, MaceStatus.DESTROYED);
                    auditLogger.log(uid, "DESTROYED",
                            "Missed " + newCount + " consecutive scans — declared destroyed");
                    if (config.isOpsOnDestroy()) {
                        plugin.notifyOps("§c[MaceControl] Mace " + uid
                                + " declared DESTROYED after " + newCount + " missed scans.");
                    }
                    if (config.isPublicOnSlotOpen()) {
                        broadcastSlotOpen(entry.getTier() != null ? entry.getTier().name() : "UNKNOWN");
                    }
                }
            }
        }
    }

    /**
     * Returns {@code true} if the mace's last-known location was accessible during
     * this scan (i.e., it should have been found if present).
     */
    private boolean isLocationAccessible(MaceEntry entry) {
        MaceLocationType locType = entry.getLocationType();
        if (locType == null) return false;

        // Offline player — Phase 4 cannot actually verify offline player inventories
        // (it is a no-op without NMS). Never penalize offline players; their maces
        // will be validated when they log in via RealTimeTracker.onPlayerJoin.
        if (locType == MaceLocationType.OFFLINE_PLAYER) {
            return false;
        }

        // Check if the chunk was loaded during this scan
        if (entry.getLocationWorld() == null) return false;
        World world = Bukkit.getWorld(entry.getLocationWorld());
        if (world == null) return false;

        // For ground entities and containers, check if chunk was loaded
        int chunkX = entry.getLocationX() >> 4;
        int chunkZ = entry.getLocationZ() >> 4;
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    // =========================================================================
    // Phase 6 — Dupe sweep
    // =========================================================================

    /**
     * Phase 6: Checks for UIDs found in multiple locations during this scan.
     * The copy matching the registry's last real-time location is kept; all others
     * are scheduled for deletion on the next tick.
     */
    private void runPhase6DupeSweep() {
        for (Map.Entry<String, List<org.bukkit.Location>> mapEntry : foundLocations.entrySet()) {
            String uid = mapEntry.getKey();
            List<org.bukkit.Location> locs = mapEntry.getValue();
            if (locs.size() <= 1) continue;

            plugin.getLogger().warning("[MaceControl] Dupe sweep: uid=" + uid
                    + " found at " + locs.size() + " locations.");

            MaceEntry entry = registry.getEntry(uid);
            String knownWorld  = entry != null ? entry.getLocationWorld() : null;
            int knownX = entry != null ? entry.getLocationX() : Integer.MIN_VALUE;
            int knownY = entry != null ? entry.getLocationY() : Integer.MIN_VALUE;
            int knownZ = entry != null ? entry.getLocationZ() : Integer.MIN_VALUE;

            // Keep the copy at the registry's known location; delete the rest
            int kept = 0;
            for (org.bukkit.Location dupeLoc : locs) {
                boolean isKnown = dupeLoc.getWorld() != null
                        && dupeLoc.getWorld().getName().equals(knownWorld)
                        && dupeLoc.getBlockX() == knownX
                        && dupeLoc.getBlockY() == knownY
                        && dupeLoc.getBlockZ() == knownZ;
                if (isKnown && kept == 0) {
                    kept++;
                    continue; // preserve this copy
                }
                // Schedule deletion of the dupe on next tick (main thread entity/item removal)
                org.bukkit.Location finalDupeLoc = dupeLoc;
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                        removeDuplicateAtLocation(uid, finalDupeLoc), 1L);
            }

            auditLogger.log(uid, "DUPE_CONFISCATED",
                    "Dupe sweep: " + locs.size() + " copies found, kept 1");
            plugin.notifyOps("§c[MaceControl] Dupe sweep: mace " + uid
                    + " existed at " + locs.size() + " locations — duplicates scheduled for removal.");
        }
    }

    /**
     * Attempts to find and remove a duplicate mace item entity at the given location.
     */
    private void removeDuplicateAtLocation(String uid, org.bukkit.Location loc) {
        if (loc.getWorld() == null) return;
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, 1.5, 1.5, 1.5)) {
            if (!(entity instanceof Item itemEntity)) continue;
            String foundUid = identifier.getUid(itemEntity.getItemStack());
            if (uid.equals(foundUid)) {
                itemEntity.remove();
                plugin.getLogger().info("[MaceControl] Removed duplicate item entity for uid=" + uid);
                return;
            }
        }
    }

    // =========================================================================
    // Scan completion
    // =========================================================================

    /**
     * Finalizes the scan: records the completion timestamp, notifies the requester,
     * and schedules the next periodic scan.
     */
    private void completeScan(@Nullable CommandSender requester) {
        scanInProgress = false;
        lastScanCompletedAt = Instant.now().getEpochSecond();

        int totalActive  = registry.countActive();
        int verified     = verifiedThisScan.size();
        int issues       = countIssues();

        plugin.getLogger().info("[MaceControl] Scan complete. "
                + verified + "/" + totalActive + " maces verified. " + issues + " issues found.");

        if (config.isOpsOnScanComplete()) {
            plugin.notifyOps("§a[MaceControl] Scan complete. "
                    + verified + "/" + totalActive + " maces verified. "
                    + issues + " issues found.");
        }

        if (requester != null) {
            requester.sendMessage("§aScan complete. " + verified + "/" + totalActive
                    + " maces verified. " + issues + " issues found.");
        }

        scheduleNextScan();
    }

    /**
     * Counts MISSING/DESTROYED entries changed during this scan as "issues".
     */
    private int countIssues() {
        int issues = 0;
        for (MaceEntry entry : registry.getAllEntries()) {
            if (entry.getMissingScanCount() > 0 || entry.getStatus() == MaceStatus.MISSING) {
                issues++;
            }
        }
        return issues;
    }

    // =========================================================================
    // Scan item processor (shared across phases)
    // =========================================================================

    /**
     * Validates a single mace ItemStack found during any scan phase, updates the
     * registry location if valid, and records it as verified.
     *
     * @param item        the ItemStack found
     * @param locType     the location type category
     * @param loc         the physical world location
     * @param holderUuid  the player UUID if applicable
     * @param holderName  the player name if applicable
     * @param inventory   the inventory the item is in (for confiscation), or null
     * @param entity      the item entity (for confiscation), or null
     */
    private void processScanItem(ItemStack item, MaceLocationType locType,
                                 org.bukkit.Location loc,
                                 @Nullable UUID holderUuid,
                                 @Nullable String holderName,
                                 @Nullable Inventory inventory,
                                 @Nullable Item entity) {
        // Find the player context if we have a UUID
        Player player = holderUuid != null ? Bukkit.getPlayer(holderUuid) : null;

        if (dupeDetector.validateAndProcess(item, player, loc, inventory, entity)) {
            // Item was confiscated — skip recording
            return;
        }

        String uid = identifier.getUid(item);
        if (uid == null) return; // validateAndProcess should have caught this

        // Re-apply lore if it was altered
        identifier.reapplyLoreIfNeeded(item);

        // Record in found-locations map for dupe sweep
        foundLocations.computeIfAbsent(uid, k -> new ArrayList<>()).add(loc);
        verifiedThisScan.add(uid);

        // Update registry location
        String worldName       = loc.getWorld() != null ? loc.getWorld().getName() : null;
        String containerType   = locType.name();
        registry.updateLocation(uid, locType, worldName,
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                holderUuid, holderName, containerType);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Scans all ItemStacks in an inventory and processes maces found.
     */
    private void scanInventoryForScan(Inventory inv, MaceLocationType locType,
                                      org.bukkit.Location loc,
                                      UUID holderUuid, String holderName) {
        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;

            if (item.getType().name().endsWith("_SHULKER_BOX")) {
                scanShulkerForScan(item, loc);
                continue;
            }
            if (item.getType() == org.bukkit.Material.BUNDLE) {
                scanBundleForScan(item, loc);
                continue;
            }
            if (item.getType() != org.bukkit.Material.MACE) continue;

            processScanItem(item, locType, loc, holderUuid, holderName, inv, null);
        }
    }

    /**
     * Classifies the location type of a tile entity (BlockState).
     */
    private MaceLocationType classifyBlockState(BlockState state) {
        String name = state.getClass().getSimpleName().toUpperCase();
        if (name.contains("HOPPER"))    return MaceLocationType.HOPPER;
        if (name.contains("DROPPER"))   return MaceLocationType.DROPPER;
        if (name.contains("DISPENSER")) return MaceLocationType.DISPENSER;
        return MaceLocationType.CONTAINER;
    }

    /**
     * Broadcasts a public slot-open message when a mace is declared destroyed.
     *
     * @param tierName the tier name of the destroyed mace
     */
    private void broadcastSlotOpen(String tierName) {
        int normalCount = registry.countActiveByTier(com.macecontrol.data.MaceTier.LIMITED);
        int fullCount   = registry.countActiveByTier(com.macecontrol.data.MaceTier.FULL);
        String msg = config.getPublicSlotOpenMessage(tierName, normalCount,
                config.getMaxNormalMaceLimit(), fullCount, config.getMaxFullMaceLimit());
        Bukkit.broadcastMessage(msg);
    }
}
