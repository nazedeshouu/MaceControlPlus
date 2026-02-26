package com.macecontrol.commands;

import com.macecontrol.MaceControl;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.AuditEntry;
import com.macecontrol.data.AuditLogger;
import com.macecontrol.data.MaceEntry;
import com.macecontrol.data.MaceLocationType;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.data.MaceStatus;
import com.macecontrol.data.MaceTier;
import com.macecontrol.tracking.MaceIdentifier;
import com.macecontrol.tracking.PeriodicScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command executor for the {@code /mace} command and all its subcommands.
 *
 * <p>The root {@code /mace} command (no arguments) requires no permission and displays
 * the public mace-slot status. Every other subcommand requires the
 * {@code macecontrol.admin} permission.</p>
 *
 * <p>All input validation is performed eagerly at the top of each handler, returning
 * immediately with a descriptive error message on invalid input.</p>
 */
public class MaceCommand implements CommandExecutor {

    // ------------------------------------------------------------------
    // Date/time formatting
    // ------------------------------------------------------------------
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /** Number of audit entries shown per page in /mace audit. */
    private static final int AUDIT_PAGE_SIZE = 20;

    // ------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------
    private final MaceRegistry    registry;
    private final MaceIdentifier  identifier;
    private final MaceConfig      config;
    private final AuditLogger     auditLogger;
    private final PeriodicScanner scanner;
    private final MaceControl     plugin;

    /**
     * Constructs a new {@link MaceCommand}.
     *
     * @param registry    the live mace registry
     * @param identifier  PDC read/write/stamp helper
     * @param config      the loaded plugin configuration
     * @param auditLogger the audit event logger
     * @param scanner     the periodic deep-scan scheduler
     * @param plugin      the owning plugin instance
     */
    public MaceCommand(MaceRegistry registry, MaceIdentifier identifier,
                       MaceConfig config, AuditLogger auditLogger,
                       PeriodicScanner scanner, MaceControl plugin) {
        this.registry    = registry;
        this.identifier  = identifier;
        this.config      = config;
        this.auditLogger = auditLogger;
        this.scanner     = scanner;
        this.plugin      = plugin;
    }

    // =========================================================================
    // CommandExecutor
    // =========================================================================

    /**
     * Entry point for all {@code /mace} invocations.
     *
     * @param sender  the command source
     * @param command the resolved Bukkit command object
     * @param label   the alias used
     * @param args    command arguments
     * @return always {@code true} — usage messages are sent inline
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            handlePublicStatus(sender);
            return true;
        }

        if (!sender.hasPermission("macecontrol.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list"     -> handleList(sender);
            case "locate"   -> handleLocate(sender, args);
            case "give"     -> handleGive(sender, args, false);
            case "gave"     -> handleGive(sender, args, true);
            case "revoke"   -> handleRevoke(sender, args);
            case "return"   -> handleReturn(sender, args);
            case "settier"  -> handleSetTier(sender, args);
            case "setslots" -> handleSetSlots(sender, args);
            case "scan"     -> handleScan(sender);
            case "info"     -> handleInfo(sender);
            case "audit"    -> handleAudit(sender, args);
            case "reload"   -> handleReload(sender);
            default         -> sender.sendMessage("§cUnknown subcommand. Use /mace for help.");
        }
        return true;
    }

    // =========================================================================
    // /mace  (no args — public)
    // =========================================================================

    /**
     * Displays the public mace-slot status message. No permission required.
     *
     * @param sender the command source
     */
    private void handlePublicStatus(CommandSender sender) {
        int totalActive   = registry.countActive();
        int totalMax      = config.getMaxTotalMaces();
        int available     = totalMax - totalActive;
        int normalCount   = registry.countActiveByTier(MaceTier.LIMITED);
        int normalMax     = config.getMaxNormalMaceLimit();
        int fullCount     = registry.countActiveByTier(MaceTier.FULL);
        int fullMax       = config.getMaxFullMaceLimit();

        sender.sendMessage("§6⚔ Mace Status: §e" + totalActive + "§7/§e" + totalMax
                + " §7in circulation §8(" + available + " available)");
        sender.sendMessage("   §7" + normalCount + "/" + normalMax
                + " standard §8| §b" + fullCount + "/" + fullMax + " legendary");
    }

    // =========================================================================
    // /mace list
    // =========================================================================

    /**
     * Displays a full registry listing sorted by UID, with per-tier counts.
     *
     * @param sender the admin command source
     */
    private void handleList(CommandSender sender) {
        int totalActive = registry.countActive();
        int totalMax    = config.getMaxTotalMaces();
        int normalCount = registry.countActiveByTier(MaceTier.LIMITED);
        int normalMax   = config.getMaxNormalMaceLimit();
        int fullCount   = registry.countActiveByTier(MaceTier.FULL);
        int fullMax     = config.getMaxFullMaceLimit();

        sender.sendMessage("§6=== Mace Registry ("
                + totalActive + "/" + totalMax + " active — "
                + normalCount + "/" + normalMax + " standard, "
                + fullCount   + "/" + fullMax   + " legendary) ===");

        Collection<MaceEntry> allEntries = registry.getAllEntries();
        if (allEntries.isEmpty()) {
            sender.sendMessage("§7No maces registered.");
            return;
        }

        List<MaceEntry> sorted = new ArrayList<>(allEntries);
        sorted.sort(Comparator.comparing(MaceEntry::getUid));

        for (MaceEntry entry : sorted) {
            String bullet      = entry.getTier() == MaceTier.FULL ? "§b●" : "§a●";
            String statusColor = statusColor(entry.getStatus());
            String statusLabel = statusColor + "§l" + entry.getStatus().name();
            String tierLabel   = "§7[" + entry.getTier().name() + "]";
            String createdDate = formatDate(entry.getCreatedAt());
            String creator     = entry.getCreatedByName() != null ? entry.getCreatedByName() : "Unknown";
            String verb        = entry.getTier() == MaceTier.FULL ? "Issued to" : "Created by";

            // Colour the UID itself using the bullet colour (strip the § prefix approach: just use same colour)
            String uidColor = entry.getTier() == MaceTier.FULL ? "§b" : "§a";

            sender.sendMessage(bullet + " " + uidColor + entry.getUid()
                    + " " + tierLabel
                    + " " + statusLabel
                    + " §8- " + verb + " " + creator + " on " + createdDate);
        }
    }

    // =========================================================================
    // /mace locate [uid|all]
    // =========================================================================

    /**
     * Shows real-time mace locations. Triggers a fresh scan if data is stale.
     *
     * @param sender the admin command source
     * @param args   command arguments (args[1] optional UID or "all")
     */
    private void handleLocate(CommandSender sender, String[] args) {
        long nowSeconds  = System.currentTimeMillis() / 1000L;
        long lastScan    = scanner.getLastScanCompletedAt();
        long staleAfter  = (long) config.getStaleThresholdMinutes() * 60L;

        if ((nowSeconds - lastScan) > staleAfter) {
            sender.sendMessage("§eLocation data is stale. Running fresh scan...");
            scanner.runImmediateScan(sender);
            return;
        }

        // Specific UID requested
        if (args.length >= 2 && !args[1].equalsIgnoreCase("all")) {
            String uid   = args[1].toUpperCase();
            MaceEntry e  = registry.getEntry(uid);
            if (e == null) {
                sender.sendMessage("§cMace §f" + uid + " §cnot found in registry.");
                return;
            }
            sender.sendMessage("§6=== Mace Location: " + uid + " ===");
            sender.sendMessage(formatLocateLine(e, nowSeconds));
            sender.sendMessage("§7Tier: " + e.getTier().name()
                    + " | Status: " + statusColor(e.getStatus()) + e.getStatus().name());
            sender.sendMessage("§7Missing scan count: " + e.getMissingScanCount());

            // Last 5 audit entries
            List<AuditEntry> history = auditLogger.getHistory(uid, 5);
            if (!history.isEmpty()) {
                sender.sendMessage("§6--- Last 5 Events ---");
                for (AuditEntry ae : history) {
                    String date   = formatDatetime(ae.getTimestamp());
                    String player = ae.getPlayerName() != null ? ae.getPlayerName() : "server";
                    String detail = ae.getDetail() != null ? ae.getDetail() : "";
                    sender.sendMessage("§8[" + date + "] §7" + ae.getEventType()
                            + "§8: §f" + detail + " §8- " + player);
                }
            }
            printScanFooter(sender, nowSeconds);
            return;
        }

        // All maces
        sender.sendMessage("§6=== Mace Locations ===");
        List<MaceEntry> all = new ArrayList<>(registry.getAllEntries());
        if (all.isEmpty()) {
            sender.sendMessage("§7No maces registered.");
            printScanFooter(sender, nowSeconds);
            return;
        }
        all.sort(Comparator.comparing(MaceEntry::getUid));
        for (MaceEntry e : all) {
            sender.sendMessage(formatLocateLine(e, nowSeconds));
        }
        printScanFooter(sender, nowSeconds);
    }

    /**
     * Formats a single locate-output line for a mace entry.
     *
     * @param e          the mace entry to format
     * @param nowSeconds current epoch seconds
     * @return formatted colour-coded line string
     */
    private String formatLocateLine(MaceEntry e, long nowSeconds) {
        String bullet    = e.getTier() == MaceTier.FULL ? "§b●" : statusColor(e.getStatus()) + "●";
        String uidColor  = e.getTier() == MaceTier.FULL ? "§b" : statusColor(e.getStatus());
        String tierLabel = "§7[" + e.getTier().name() + "]";
        String location  = buildLocationString(e);
        String verifiedAgo = formatAgo(nowSeconds - e.getLastVerifiedAt());

        return bullet + " " + uidColor + e.getUid() + " " + tierLabel
                + " §f" + location + " §8(verified " + verifiedAgo + " ago)";
    }

    /**
     * Appends the scan timing footer to the locate output.
     *
     * @param sender     the command source
     * @param nowSeconds current epoch seconds
     */
    private void printScanFooter(CommandSender sender, long nowSeconds) {
        long lastScan = scanner.getLastScanCompletedAt();
        long nextScan = scanner.getNextScanScheduledAt();

        String lastAgo = lastScan > 0 ? formatAgo(nowSeconds - lastScan) : "never";
        String nextIn;
        if (nextScan > 0) {
            long diffSeconds = nextScan - nowSeconds;
            if (diffSeconds <= 0) {
                nextIn = "imminent";
            } else {
                double hours = diffSeconds / 3600.0;
                nextIn = "~" + String.format("%.1f", hours) + "h";
            }
        } else {
            nextIn = "unknown";
        }
        sender.sendMessage("§7Last scan: " + lastAgo + " | Next scan: " + nextIn);
    }

    // =========================================================================
    // /mace give <player> <tier>   and   /mace gave <player> <tier>
    // =========================================================================

    /**
     * Creates a new tracked mace and delivers it to an online player.
     *
     * <p>When {@code silent} is {@code false} (/mace give), a public broadcast and
     * an ops notification are sent. When {@code silent} is {@code true} (/mace gave),
     * neither is sent.</p>
     *
     * @param sender the admin command source
     * @param args   command arguments
     * @param silent {@code true} for /mace gave (no broadcast/ops notification)
     */
    private void handleGive(CommandSender sender, String[] args, boolean silent) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /mace " + (silent ? "gave" : "give") + " <player> <tier>");
            return;
        }

        String playerName = args[1];
        String tierStr    = args[2];

        Player recipient = Bukkit.getPlayerExact(playerName);
        if (recipient == null) {
            sender.sendMessage("§cPlayer §f" + playerName + " §cis not online.");
            return;
        }

        MaceTier tier = MaceTier.fromString(tierStr);
        if (tier == null) {
            sender.sendMessage("§cInvalid tier §f" + tierStr + "§c. Use LIMITED or FULL.");
            return;
        }

        // Per-tier cap check
        if (tier == MaceTier.LIMITED) {
            int current = registry.countActiveByTier(MaceTier.LIMITED);
            int max     = config.getMaxNormalMaceLimit();
            if (current >= max) {
                sender.sendMessage("§cStandard mace cap reached (" + current + "/" + max + ").");
                return;
            }
        } else {
            int current = registry.countActiveByTier(MaceTier.FULL);
            int max     = config.getMaxFullMaceLimit();
            if (current >= max) {
                sender.sendMessage("§cLegendary mace cap reached (" + current + "/" + max + ").");
                return;
            }
        }

        // Generate UID and create the item
        String uid        = registry.generateNextUid();
        long   createdAt  = System.currentTimeMillis() / 1000L;
        ItemStack mace    = identifier.createTrackedMace(uid, tier);

        // Register in the registry
        UUID adminUuid   = sender instanceof Player p ? p.getUniqueId() : null;
        String adminName = sender.getName();

        MaceEntry entry = new MaceEntry(uid, tier, createdAt, adminUuid, adminName);
        registry.register(entry);

        // Deliver item
        Map<Integer, ItemStack> leftover = recipient.getInventory().addItem(mace);
        if (!leftover.isEmpty()) {
            recipient.getWorld().dropItemNaturally(recipient.getLocation(), leftover.get(0));
            sender.sendMessage("§ePlayer's inventory is full. Mace dropped at their feet.");
        }

        // Update registry location to PLAYER_INVENTORY
        registry.updateLocation(uid, MaceLocationType.PLAYER_INVENTORY,
                recipient.getWorld().getName(),
                recipient.getLocation().getBlockX(),
                recipient.getLocation().getBlockY(),
                recipient.getLocation().getBlockZ(),
                recipient.getUniqueId(), recipient.getName(), null);

        // Audit log
        auditLogger.log(uid, "ISSUED",
                "Issued by " + adminName + " to " + recipient.getName() + " (" + tier.name() + ")",
                adminUuid, adminName, recipient.getLocation());

        if (!silent) {
            // Ops notification
            if (config.isOpsOnCraft()) {
                plugin.notifyOps("§e[MaceControl] " + adminName + " issued Mace " + uid
                        + " (" + tier.name() + ") to " + recipient.getName() + ".");
            }
            // Public broadcast
            int normalCount = registry.countActiveByTier(MaceTier.LIMITED);
            int fullCount   = registry.countActiveByTier(MaceTier.FULL);
            String msg = config.formatCraftMessage(
                    recipient.getDisplayName(),
                    normalCount, config.getMaxNormalMaceLimit(),
                    fullCount,   config.getMaxFullMaceLimit());
            Bukkit.broadcastMessage(msg);
            sender.sendMessage("§aMace " + uid + " (" + tier.name() + ") given to "
                    + recipient.getName() + ".");
        } else {
            sender.sendMessage("§aMace " + uid + " (" + tier.name() + ") quietly issued to "
                    + recipient.getName() + ".");
        }
    }

    // =========================================================================
    // /mace revoke <uid>
    // =========================================================================

    /**
     * Revokes a mace by UID — removes the physical item from the world and marks
     * the entry as {@link MaceStatus#REVOKED}.
     *
     * @param sender the admin command source
     * @param args   command arguments
     */
    private void handleRevoke(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mace revoke <uid>");
            return;
        }
        String uid   = args[1].toUpperCase();
        MaceEntry e  = registry.getEntry(uid);
        if (e == null || e.getStatus() != MaceStatus.ACTIVE) {
            sender.sendMessage("§cMace §f" + uid + " §cnot found or not active.");
            return;
        }

        boolean removed = findAndRemovePhysicalItem(e, sender, true);

        if (!removed) {
            // Still proceed with registry revocation even if physical item not found
            sender.sendMessage("§ePhysical item could not be located — revoking registry entry anyway.");
        }

        registry.setStatus(uid, MaceStatus.REVOKED);

        String adminName = sender.getName();
        UUID   adminUuid = sender instanceof Player p ? p.getUniqueId() : null;
        auditLogger.log(uid, "REVOKED",
                "Revoked by " + adminName,
                adminUuid, adminName, null);

        sender.sendMessage("§aMace §f" + uid + " §ahas been revoked.");
    }

    // =========================================================================
    // /mace return <uid>
    // =========================================================================

    /**
     * Teleports a mace from wherever it is into the admin's inventory.
     *
     * @param sender the admin command source (must be a Player)
     * @param args   command arguments
     */
    private void handleReturn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage("§cThis command can only be used by a player.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mace return <uid>");
            return;
        }
        String uid   = args[1].toUpperCase();
        MaceEntry e  = registry.getEntry(uid);
        if (e == null || e.getStatus() != MaceStatus.ACTIVE) {
            sender.sendMessage("§cMace §f" + uid + " §cnot found or not active.");
            return;
        }

        ItemStack recovered = findAndExtractPhysicalItem(e);
        if (recovered == null) {
            sender.sendMessage("§ePhysical item could not be located in the world.");
            return;
        }

        // Give to admin
        Map<Integer, ItemStack> leftover = admin.getInventory().addItem(recovered);
        if (!leftover.isEmpty()) {
            admin.getWorld().dropItemNaturally(admin.getLocation(), leftover.get(0));
            admin.sendMessage("§eYour inventory is full. Mace dropped at your feet.");
        }

        // Update location
        registry.updateLocation(uid, MaceLocationType.PLAYER_INVENTORY,
                admin.getWorld().getName(),
                admin.getLocation().getBlockX(),
                admin.getLocation().getBlockY(),
                admin.getLocation().getBlockZ(),
                admin.getUniqueId(), admin.getName(), null);

        auditLogger.log(uid, "ADMIN_RETURN",
                "Returned by " + admin.getName(),
                admin.getUniqueId(), admin.getName(), admin.getLocation());

        sender.sendMessage("§aMace §f" + uid + " §ahas been returned to your inventory.");
    }

    // =========================================================================
    // /mace settier <uid> <tier>
    // =========================================================================

    /**
     * Changes the enchantment tier of an existing active mace.
     *
     * @param sender the admin command source
     * @param args   command arguments
     */
    private void handleSetTier(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /mace settier <uid> <tier>");
            return;
        }
        String uid    = args[1].toUpperCase();
        String tierStr = args[2];

        MaceEntry e = registry.getEntry(uid);
        if (e == null || e.getStatus() != MaceStatus.ACTIVE) {
            sender.sendMessage("§cMace §f" + uid + " §cnot found or not active.");
            return;
        }

        MaceTier newTier = MaceTier.fromString(tierStr);
        if (newTier == null) {
            sender.sendMessage("§cInvalid tier §f" + tierStr + "§c. Use LIMITED or FULL.");
            return;
        }

        if (e.getTier() == newTier) {
            sender.sendMessage("§eMace §f" + uid + " §eis already " + newTier.name() + ".");
            return;
        }

        // Cap check for new tier
        if (newTier == MaceTier.FULL) {
            int current = registry.countActiveByTier(MaceTier.FULL);
            int max     = config.getMaxFullMaceLimit();
            if (current >= max) {
                sender.sendMessage("§cLegendary mace cap reached (" + current + "/" + max
                        + "). Cannot change tier to FULL.");
                return;
            }
        } else {
            int current = registry.countActiveByTier(MaceTier.LIMITED);
            int max     = config.getMaxNormalMaceLimit();
            if (current >= max) {
                sender.sendMessage("§cStandard mace cap reached (" + current + "/" + max
                        + "). Cannot change tier to LIMITED.");
                return;
            }
        }

        // Find and update the physical item
        ItemStack physicalItem = locatePhysicalItem(e);
        if (physicalItem != null) {
            // Update the tier PDC key directly before recomputing the checksum.
            // MaceIdentifier.recomputeChecksum reads the tier from PDC, so it must be
            // updated first; then the checksum and lore are reapplied consistently.
            ItemMeta meta = physicalItem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(
                        new NamespacedKey("macecontrol", "tier"),
                        PersistentDataType.STRING,
                        newTier.name());
                physicalItem.setItemMeta(meta);
            }
            identifier.recomputeChecksum(physicalItem);
            identifier.applyLore(physicalItem, newTier, uid);
        } else {
            sender.sendMessage("§ePhysical item not found in loaded area — tier updated in registry only.");
        }

        // Update registry entry tier
        registry.setTier(uid, newTier);

        String adminName = sender.getName();
        UUID   adminUuid = sender instanceof Player p ? p.getUniqueId() : null;
        auditLogger.log(uid, "TIER_CHANGE",
                "Changed from " + e.getTier().name() + " to " + newTier.name() + " by " + adminName,
                adminUuid, adminName, null);

        sender.sendMessage("§aMace §f" + uid + " §atier changed to " + newTier.name() + ".");
    }

    // =========================================================================
    // /mace setslots <tier> <number>
    // =========================================================================

    /**
     * Updates the maximum mace slot count for a given tier in both memory and on disk.
     *
     * @param sender the admin command source
     * @param args   command arguments
     */
    private void handleSetSlots(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /mace setslots <LIMITED|FULL> <number>");
            return;
        }

        MaceTier tier = MaceTier.fromString(args[1]);
        if (tier == null) {
            sender.sendMessage("§cInvalid tier §f" + args[1] + "§c. Use LIMITED or FULL.");
            return;
        }

        int number;
        try {
            number = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§c§f" + args[2] + " §cis not a valid number.");
            return;
        }
        if (number <= 0) {
            sender.sendMessage("§cSlot count must be greater than 0.");
            return;
        }

        int activeCount = registry.countActiveByTier(tier);
        if (activeCount > number) {
            sender.sendMessage("§eWarning: " + activeCount + " " + tier.name()
                    + " maces are active, exceeding new limit of " + number
                    + ". No new " + tier.name() + " maces will be created until the count drops.");
        }

        // Persist to disk and update in-memory config
        if (tier == MaceTier.LIMITED) {
            plugin.getConfig().set("max-normal-mace-limit", number);
        } else {
            plugin.getConfig().set("max-full-mace-limit", number);
        }
        plugin.saveConfig();

        sender.sendMessage("§aMax " + tier.name() + " maces set to " + number + ".");
    }

    // =========================================================================
    // /mace scan
    // =========================================================================

    /**
     * Triggers an immediate full scan and delegates completion feedback to the scanner.
     *
     * @param sender the admin command source
     */
    private void handleScan(CommandSender sender) {
        sender.sendMessage("§eStarting manual scan...");
        scanner.runImmediateScan(sender);
    }

    // =========================================================================
    // /mace info
    // =========================================================================

    /**
     * Shows detailed tracking info for the mace the player is currently holding.
     *
     * @param sender the admin command source (must be a Player)
     */
    private void handleInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by a player.");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.MACE) {
            player.sendMessage("§cYou must be holding a mace.");
            return;
        }

        String uid = identifier.getUid(held);
        if (uid == null) {
            player.sendMessage("§cThis mace is unregistered. It will be confiscated.");
            return;
        }

        if (!identifier.verifyChecksum(held)) {
            player.sendMessage("§cThis mace has been tampered with. It will be confiscated.");
            return;
        }

        MaceEntry e = registry.getEntry(uid);
        if (e == null) {
            player.sendMessage("§cUID §f" + uid + " §cis not in the registry.");
            return;
        }

        long nowSeconds  = System.currentTimeMillis() / 1000L;
        String created   = formatDate(e.getCreatedAt());
        String creator   = e.getCreatedByName() != null ? e.getCreatedByName() : "Unknown";
        String location  = buildLocationString(e);
        String lastVerif = formatAgo(nowSeconds - e.getLastVerifiedAt());

        player.sendMessage("§6=== Mace Info: " + uid + " ===");
        player.sendMessage("§7Tier: " + tierDisplayName(e.getTier()));
        player.sendMessage("§7Status: " + statusColor(e.getStatus()) + e.getStatus().name());
        player.sendMessage("§7Created by: " + creator + " on " + created);
        player.sendMessage("§7Location: " + location);
        player.sendMessage("§7Missing scan count: " + e.getMissingScanCount());
        player.sendMessage("§7Last verified: " + lastVerif + " ago");

        List<AuditEntry> history = auditLogger.getHistory(uid, 5);
        if (!history.isEmpty()) {
            player.sendMessage("§6--- Last 5 Events ---");
            for (AuditEntry ae : history) {
                String date   = formatDatetime(ae.getTimestamp());
                String detail = ae.getDetail() != null ? ae.getDetail() : "";
                player.sendMessage("§8[" + date + "] §7" + ae.getEventType() + "§8: §f" + detail);
            }
        }
    }

    // =========================================================================
    // /mace audit <uid> [page]
    // =========================================================================

    /**
     * Shows a paginated audit history for a given mace UID.
     *
     * <p>Uses Adventure {@link Component} with {@link ClickEvent} for clickable page navigation
     * when the sender is a {@link Player}. Console senders receive a plain text hint instead.</p>
     *
     * @param sender the admin command source
     * @param args   command arguments
     */
    private void handleAudit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /mace audit <uid> [page]");
            return;
        }

        String uid = args[1].toUpperCase();

        int page = 1;
        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
                if (page < 1) page = 1;
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cInvalid page number: §f" + args[2]);
                return;
            }
        }

        // Fetch enough rows to know if there is a next page
        int limit  = page * AUDIT_PAGE_SIZE + 1;
        List<AuditEntry> all = auditLogger.getHistory(uid, limit);

        if (all.isEmpty()) {
            sender.sendMessage("§7No audit entries found for §f" + uid + "§7.");
            return;
        }

        int totalFetched = all.size();
        int startIdx     = (page - 1) * AUDIT_PAGE_SIZE;
        if (startIdx >= totalFetched) {
            sender.sendMessage("§cPage " + page + " does not exist for §f" + uid + "§c.");
            return;
        }

        int endIdx       = Math.min(startIdx + AUDIT_PAGE_SIZE, totalFetched);
        boolean hasNext  = totalFetched > endIdx;

        List<AuditEntry> pageEntries = all.subList(startIdx, endIdx);

        sender.sendMessage("§6=== Audit Log: " + uid + " (page " + page + ") ===");
        for (AuditEntry ae : pageEntries) {
            String date     = formatDatetime(ae.getTimestamp());
            String player   = ae.getPlayerName() != null ? ae.getPlayerName() : "server";
            String detail   = ae.getDetail() != null ? ae.getDetail() : "";
            sender.sendMessage("§8[" + date + "] §7" + ae.getEventType()
                    + " §f" + detail + " §8- " + player);
        }

        // Clickable pagination (Adventure API — only meaningful for players)
        if (sender instanceof Player player) {
            if (hasNext) {
                int nextPage    = page + 1;
                String nextCmd  = "/mace audit " + uid + " " + nextPage;
                Component nextBtn = Component.text("[Next Page →]")
                        .color(NamedTextColor.GOLD)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.runCommand(nextCmd));

                if (page > 1) {
                    int prevPage   = page - 1;
                    String prevCmd = "/mace audit " + uid + " " + prevPage;
                    Component prevBtn = Component.text("[← Prev Page] ")
                            .color(NamedTextColor.GRAY)
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.runCommand(prevCmd));
                    player.sendMessage(prevBtn.append(nextBtn));
                } else {
                    player.sendMessage(nextBtn);
                }
            } else if (page > 1) {
                int prevPage   = page - 1;
                String prevCmd = "/mace audit " + uid + " " + prevPage;
                Component prevBtn = Component.text("[← Prev Page]")
                        .color(NamedTextColor.GRAY)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.runCommand(prevCmd));
                player.sendMessage(prevBtn);
            }
        } else {
            // Console: print plain next-page hint
            if (hasNext) {
                sender.sendMessage("§7Run /mace audit " + uid + " " + (page + 1) + " for the next page.");
            }
        }
    }

    // =========================================================================
    // /mace reload
    // =========================================================================

    /**
     * Reloads the plugin configuration from disk and reschedules the periodic scan.
     *
     * @param sender the admin command source
     */
    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        scanner.scheduleNextScan();
        sender.sendMessage("§aMaceControl config reloaded.");
    }

    // =========================================================================
    // Physical item location helpers
    // =========================================================================

    /**
     * Locates the physical {@link ItemStack} for a registry entry without removing it.
     * Returns {@code null} if the location is unloaded, the holder is offline,
     * or no matching item is found.
     *
     * @param e the registry entry describing where the mace should be
     * @return the located ItemStack or {@code null}
     */
    private ItemStack locatePhysicalItem(MaceEntry e) {
        String uid = e.getUid();

        switch (e.getLocationType()) {
            case PLAYER_INVENTORY -> {
                Player holder = holderOnline(e);
                if (holder == null) return null;
                return findInInventory(holder.getInventory(), uid);
            }
            case PLAYER_ENDERCHEST -> {
                Player holder = holderOnline(e);
                if (holder == null) return null;
                return findInInventory(holder.getEnderChest(), uid);
            }
            case OFFLINE_PLAYER -> {
                return null; // Cannot access offline player inventory without async NBT read
            }
            case CONTAINER, HOPPER, DROPPER, DISPENSER -> {
                Block block = resolveBlock(e);
                if (block == null) return null;
                if (block.getState() instanceof Container container) {
                    return findInInventory(container.getInventory(), uid);
                }
                return null;
            }
            case GROUND_ENTITY -> {
                return findGroundItem(e, uid);
            }
            case ITEM_FRAME -> {
                ItemFrame frame = findItemFrame(e);
                if (frame == null) return null;
                ItemStack item = frame.getItem();
                if (item != null && uid.equals(identifier.getUid(item))) {
                    return item;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Finds and removes the physical mace item from the world.
     * Used by /mace return, which needs the actual item object.
     *
     * @param e the registry entry describing the item's location
     * @return the extracted ItemStack, or {@code null} if not found/accessible
     */
    private ItemStack findAndExtractPhysicalItem(MaceEntry e) {
        String uid = e.getUid();

        switch (e.getLocationType()) {
            case PLAYER_INVENTORY -> {
                Player holder = holderOnline(e);
                if (holder == null) return null;
                return extractFromInventory(holder.getInventory(), uid);
            }
            case PLAYER_ENDERCHEST -> {
                Player holder = holderOnline(e);
                if (holder == null) return null;
                return extractFromInventory(holder.getEnderChest(), uid);
            }
            case OFFLINE_PLAYER -> {
                return null;
            }
            case CONTAINER, HOPPER, DROPPER, DISPENSER -> {
                Block block = resolveBlock(e);
                if (block == null) return null;
                if (block.getState() instanceof Container container) {
                    return extractFromInventory(container.getInventory(), uid);
                }
                return null;
            }
            case GROUND_ENTITY -> {
                ItemStack found = findGroundItem(e, uid);
                if (found == null) return null;
                // Remove the entity
                removeGroundItem(e, uid);
                return found;
            }
            case ITEM_FRAME -> {
                ItemFrame frame = findItemFrame(e);
                if (frame == null) return null;
                ItemStack item = frame.getItem();
                if (item != null && uid.equals(identifier.getUid(item))) {
                    frame.setItem(null);
                    return item;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Finds and removes the physical mace item, with optional player notification on player locations.
     * Used by /mace revoke.
     *
     * @param e      the registry entry describing the item's location
     * @param sender the admin to notify about offline-player queuing
     * @param notify whether to send a revocation message to a holding player
     * @return {@code true} if the physical item was successfully found and removed
     */
    private boolean findAndRemovePhysicalItem(MaceEntry e, CommandSender sender, boolean notify) {
        String uid = e.getUid();

        switch (e.getLocationType()) {
            case PLAYER_INVENTORY -> {
                Player holder = holderOnline(e);
                if (holder == null) return false;
                boolean removed = extractFromInventory(holder.getInventory(), uid) != null;
                if (removed && notify) {
                    holder.sendMessage("§cA mace you held has been revoked by an administrator.");
                }
                return removed;
            }
            case PLAYER_ENDERCHEST -> {
                Player holder = holderOnline(e);
                if (holder == null) return false;
                boolean removed = extractFromInventory(holder.getEnderChest(), uid) != null;
                if (removed && notify) {
                    holder.sendMessage("§cA mace you held has been revoked by an administrator.");
                }
                return removed;
            }
            case OFFLINE_PLAYER -> {
                UUID holderUuid = e.getLocationHolderUuid();
                if (holderUuid != null) {
                    registry.queueRevocation(holderUuid, uid);
                }
                sender.sendMessage("§ePlayer is offline — revocation queued and will execute on next login.");
                return true; // Treat as "handled"
            }
            case CONTAINER, HOPPER, DROPPER, DISPENSER -> {
                Block block = resolveBlock(e);
                if (block == null) return false;
                if (block.getState() instanceof Container container) {
                    return extractFromInventory(container.getInventory(), uid) != null;
                }
                return false;
            }
            case GROUND_ENTITY -> {
                return removeGroundItem(e, uid);
            }
            case ITEM_FRAME -> {
                ItemFrame frame = findItemFrame(e);
                if (frame == null) return false;
                ItemStack item = frame.getItem();
                if (item != null && uid.equals(identifier.getUid(item))) {
                    frame.setItem(null);
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    // =========================================================================
    // Inventory / entity search utilities
    // =========================================================================

    /**
     * Searches an inventory for the first ItemStack whose PDC UID matches.
     *
     * @param inventory the inventory to search
     * @param uid       the UID to find
     * @return the matching ItemStack, or {@code null}
     */
    private ItemStack findInInventory(Inventory inventory, String uid) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == Material.MACE) {
                if (uid.equals(identifier.getUid(item))) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * Removes and returns the first ItemStack in an inventory whose PDC UID matches.
     *
     * @param inventory the inventory to search and modify
     * @param uid       the UID to find
     * @return a copy of the removed ItemStack, or {@code null} if not found
     */
    private ItemStack extractFromInventory(Inventory inventory, String uid) {
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == Material.MACE) {
                if (uid.equals(identifier.getUid(item))) {
                    ItemStack copy = item.clone();
                    inventory.setItem(i, null);
                    return copy;
                }
            }
        }
        return null;
    }

    /**
     * Resolves the world Block at the stored location of a registry entry.
     * Returns {@code null} if the world is not loaded or the chunk is not loaded.
     *
     * @param e the registry entry
     * @return the Block, or {@code null}
     */
    private Block resolveBlock(MaceEntry e) {
        if (e.getLocationWorld() == null) return null;
        World world = Bukkit.getWorld(e.getLocationWorld());
        if (world == null) return null;
        if (!world.isChunkLoaded(e.getLocationX() >> 4, e.getLocationZ() >> 4)) return null;
        return world.getBlockAt(e.getLocationX(), e.getLocationY(), e.getLocationZ());
    }

    /**
     * Scans nearby Item entities for one matching the given UID.
     *
     * @param e   the registry entry (provides world and coordinates)
     * @param uid the UID to match
     * @return the ItemStack of the matching entity, or {@code null}
     */
    private ItemStack findGroundItem(MaceEntry e, String uid) {
        if (e.getLocationWorld() == null) return null;
        World world = Bukkit.getWorld(e.getLocationWorld());
        if (world == null) return null;
        Location loc = new Location(world, e.getLocationX(), e.getLocationY(), e.getLocationZ());
        for (Entity entity : world.getNearbyEntities(loc, 2, 2, 2)) {
            if (entity instanceof Item dropped) {
                ItemStack item = dropped.getItemStack();
                if (item.getType() == Material.MACE && uid.equals(identifier.getUid(item))) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * Removes a ground Item entity matching the given UID near the stored location.
     *
     * @param e   the registry entry providing world and coordinates
     * @param uid the UID to match
     * @return {@code true} if an entity was found and removed
     */
    private boolean removeGroundItem(MaceEntry e, String uid) {
        if (e.getLocationWorld() == null) return false;
        World world = Bukkit.getWorld(e.getLocationWorld());
        if (world == null) return false;
        Location loc = new Location(world, e.getLocationX(), e.getLocationY(), e.getLocationZ());
        for (Entity entity : world.getNearbyEntities(loc, 2, 2, 2)) {
            if (entity instanceof Item dropped) {
                ItemStack item = dropped.getItemStack();
                if (item.getType() == Material.MACE && uid.equals(identifier.getUid(item))) {
                    entity.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Finds an ItemFrame entity at the stored location of a registry entry.
     *
     * @param e the registry entry providing world and coordinates
     * @return the ItemFrame, or {@code null}
     */
    private ItemFrame findItemFrame(MaceEntry e) {
        if (e.getLocationWorld() == null) return null;
        World world = Bukkit.getWorld(e.getLocationWorld());
        if (world == null) return null;
        Location loc = new Location(world, e.getLocationX(), e.getLocationY(), e.getLocationZ());
        for (Entity entity : world.getNearbyEntities(loc, 1, 1, 1)) {
            if (entity instanceof ItemFrame frame) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Returns the online Player matching the entry's location holder UUID, or {@code null}.
     *
     * @param e the registry entry
     * @return online Player, or {@code null} if offline or UUID is null
     */
    private Player holderOnline(MaceEntry e) {
        UUID uuid = e.getLocationHolderUuid();
        if (uuid == null) return null;
        return Bukkit.getPlayer(uuid);
    }

    // =========================================================================
    // Formatting helpers
    // =========================================================================

    /**
     * Builds a human-readable location string for a mace entry.
     *
     * @param e the entry whose location should be described
     * @return a short description such as "In Steve's inventory" or "Chest at world 0, 64, 0"
     */
    private String buildLocationString(MaceEntry e) {
        String holder = e.getLocationHolderName() != null ? e.getLocationHolderName() : "Unknown";
        String world  = e.getLocationWorld() != null ? e.getLocationWorld() : "?";
        int x = e.getLocationX(), y = e.getLocationY(), z = e.getLocationZ();

        return switch (e.getLocationType()) {
            case PLAYER_INVENTORY  -> "In " + holder + "'s inventory";
            case PLAYER_ENDERCHEST -> "Ender chest: " + holder;
            case OFFLINE_PLAYER    -> "Offline: " + holder;
            case CONTAINER         -> {
                String ct = e.getLocationContainerType() != null
                        ? e.getLocationContainerType() : "Container";
                yield ct + " at " + world + " " + x + ", " + y + ", " + z;
            }
            case HOPPER            -> "Hopper at " + world + " " + x + ", " + y + ", " + z;
            case DROPPER           -> "Dropper at " + world + " " + x + ", " + y + ", " + z;
            case DISPENSER         -> "Dispenser at " + world + " " + x + ", " + y + ", " + z;
            case GROUND_ENTITY     -> "On ground at " + world + " " + x + ", " + y + ", " + z;
            case ITEM_FRAME        -> "Item frame at " + world + " " + x + ", " + y + ", " + z;
            case MINECART_CHEST    -> "Minecart chest at " + world + " " + x + ", " + y + ", " + z;
            case MINECART_HOPPER   -> "Minecart hopper at " + world + " " + x + ", " + y + ", " + z;
            case SHULKER_ITEM      -> "Shulker item at " + world + " " + x + ", " + y + ", " + z;
            case BUNDLE            -> "Bundle at " + world + " " + x + ", " + y + ", " + z;
            case UNKNOWN           -> "Unknown location";
        };
    }

    /**
     * Returns the appropriate colour code for a mace status.
     *
     * @param status the mace lifecycle status
     * @return a Minecraft colour code string
     */
    private String statusColor(MaceStatus status) {
        return switch (status) {
            case ACTIVE   -> "§a";
            case MISSING  -> "§e";
            case DESTROYED, REVOKED -> "§c";
        };
    }

    /**
     * Returns the display name for a tier (e.g. "Standard (LIMITED)" or "Legendary (FULL)").
     *
     * @param tier the mace tier
     * @return a formatted display string
     */
    private String tierDisplayName(MaceTier tier) {
        return switch (tier) {
            case LIMITED -> "§7Standard (LIMITED)";
            case FULL    -> "§bLegendary (FULL)";
        };
    }

    /**
     * Formats a Unix epoch-second value as a date string (yyyy-MM-dd).
     *
     * @param epochSeconds seconds since the Unix epoch
     * @return formatted date string
     */
    private String formatDate(long epochSeconds) {
        if (epochSeconds <= 0) return "unknown";
        return DATE_FMT.format(Instant.ofEpochSecond(epochSeconds));
    }

    /**
     * Formats a Unix epoch-second value as a datetime string (yyyy-MM-dd HH:mm).
     *
     * @param epochSeconds seconds since the Unix epoch
     * @return formatted datetime string
     */
    private String formatDatetime(long epochSeconds) {
        if (epochSeconds <= 0) return "unknown";
        return DATETIME_FMT.format(Instant.ofEpochSecond(epochSeconds));
    }

    /**
     * Formats a duration in seconds as a human-readable "X min ago" or "Xh ago" string.
     * Negative values are clamped to zero (future timestamps are shown as "just now").
     *
     * @param elapsedSeconds the number of seconds that have elapsed
     * @return a short human-readable string such as "5 min" or "2.3h"
     */
    private String formatAgo(long elapsedSeconds) {
        if (elapsedSeconds <= 0) return "just now";
        if (elapsedSeconds < 60) return elapsedSeconds + "s";
        long minutes = elapsedSeconds / 60;
        if (minutes < 60) return minutes + " min";
        double hours = elapsedSeconds / 3600.0;
        return String.format("%.1fh", hours);
    }
}
