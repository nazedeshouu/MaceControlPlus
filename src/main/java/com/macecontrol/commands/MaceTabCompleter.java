package com.macecontrol.commands;

import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.MaceEntry;
import com.macecontrol.data.MaceRegistry;
import com.macecontrol.data.MaceStatus;
import com.macecontrol.data.MaceTier;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tab completer for the {@code /mace} command and all its subcommands.
 *
 * <p>Non-admin senders receive an empty completion list for all subcommands.
 * Admin completions are filtered by the partial string the sender has already typed.</p>
 *
 * <p>Registered via {@code plugin.getCommand("mace").setTabCompleter(this)} in the main
 * class. This class has no mutable state — every completion call reads live data from
 * the provided {@link MaceRegistry} and {@link MaceConfig}.</p>
 */
public class MaceTabCompleter implements TabCompleter {

    /** All admin-visible top-level subcommands. */
    private static final List<String> ADMIN_SUBCOMMANDS = Arrays.asList(
            "list", "locate", "give", "gave", "revoke", "return",
            "settier", "setslots", "scan", "info", "audit", "reload"
    );

    /** The two valid tier names, upper-cased to match MaceTier enum. */
    private static final List<String> TIER_COMPLETIONS = Arrays.asList("LIMITED", "FULL");

    private final MaceRegistry registry;
    private final MaceConfig   config;

    /**
     * Constructs a new {@link MaceTabCompleter}.
     *
     * @param registry the live mace registry, used to enumerate UIDs
     * @param config   the plugin configuration, used to read current slot limits
     */
    public MaceTabCompleter(MaceRegistry registry, MaceConfig config) {
        this.registry = registry;
        this.config   = config;
    }

    // =========================================================================
    // TabCompleter
    // =========================================================================

    /**
     * Provides tab-completion suggestions for the {@code /mace} command.
     *
     * <p>Returns an empty list (not {@code null}) whenever there is nothing sensible to
     * suggest, in order to suppress the default file-system completion.</p>
     *
     * @param sender  the command source
     * @param command the resolved command object
     * @param alias   the alias used
     * @param args    the arguments typed so far (always at least length 1 when called)
     * @return a mutable, already-filtered list of completion strings; never {@code null}
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        // args[0] is always present when this method fires (partial or empty sub-command token)
        if (args.length == 1) {
            // Non-admins see nothing
            if (!sender.hasPermission("macecontrol.admin")) {
                return Collections.emptyList();
            }
            return filterByPrefix(ADMIN_SUBCOMMANDS, args[0]);
        }

        // No further completions for non-admins
        if (!sender.hasPermission("macecontrol.admin")) {
            return Collections.emptyList();
        }

        String sub = args[0].toLowerCase();

        return switch (sub) {
            case "give", "gave" -> completeGive(args);
            case "revoke"       -> completeActiveUids(args, 1);
            case "return"       -> completeActiveUids(args, 1);
            case "locate"       -> completeLocate(args);
            case "settier"      -> completeSetTier(args);
            case "audit"        -> completeAudit(args);
            case "setslots"     -> completeSetSlots(args);
            default             -> Collections.emptyList();
        };
    }

    // =========================================================================
    // Per-subcommand completers
    // =========================================================================

    /**
     * Completions for {@code /mace give <player> <tier>} and
     * {@code /mace gave <player> <tier>}.
     *
     * <ul>
     *   <li>args[1] (2nd token): online player names</li>
     *   <li>args[2] (3rd token): LIMITED | FULL</li>
     * </ul>
     *
     * @param args the full args array
     * @return filtered completion list
     */
    private List<String> completeGive(String[] args) {
        if (args.length == 2) {
            return filterByPrefix(onlinePlayerNames(), args[1]);
        }
        if (args.length == 3) {
            return filterByPrefix(TIER_COMPLETIONS, args[2]);
        }
        return Collections.emptyList();
    }

    /**
     * Completions for {@code /mace locate [uid|all]}.
     *
     * <ul>
     *   <li>args[1]: ACTIVE mace UIDs + "all"</li>
     * </ul>
     *
     * @param args the full args array
     * @return filtered completion list
     */
    private List<String> completeLocate(String[] args) {
        if (args.length == 2) {
            List<String> options = new ArrayList<>(activeUids());
            options.add("all");
            return filterByPrefix(options, args[1]);
        }
        return Collections.emptyList();
    }

    /**
     * Completions for {@code /mace settier <uid> <tier>}.
     *
     * <ul>
     *   <li>args[1]: ACTIVE mace UIDs</li>
     *   <li>args[2]: LIMITED | FULL</li>
     * </ul>
     *
     * @param args the full args array
     * @return filtered completion list
     */
    private List<String> completeSetTier(String[] args) {
        if (args.length == 2) {
            return filterByPrefix(activeUids(), args[1]);
        }
        if (args.length == 3) {
            return filterByPrefix(TIER_COMPLETIONS, args[2]);
        }
        return Collections.emptyList();
    }

    /**
     * Completions for {@code /mace audit <uid> [page]}.
     *
     * <ul>
     *   <li>args[1]: all registered UIDs (including inactive)</li>
     *   <li>args[2]+: no suggestion (page numbers are numeric)</li>
     * </ul>
     *
     * @param args the full args array
     * @return filtered completion list
     */
    private List<String> completeAudit(String[] args) {
        if (args.length == 2) {
            return filterByPrefix(allUids(), args[1]);
        }
        return Collections.emptyList();
    }

    /**
     * Completions for {@code /mace setslots <tier> <number>}.
     *
     * <ul>
     *   <li>args[1]: LIMITED | FULL</li>
     *   <li>args[2]: the current configured limit value for the chosen tier</li>
     * </ul>
     *
     * @param args the full args array
     * @return filtered completion list
     */
    private List<String> completeSetSlots(String[] args) {
        if (args.length == 2) {
            return filterByPrefix(TIER_COMPLETIONS, args[1]);
        }
        if (args.length == 3) {
            MaceTier tier = MaceTier.fromString(args[1]);
            if (tier == null) return Collections.emptyList();
            int currentMax = tier == MaceTier.LIMITED
                    ? config.getMaxNormalMaceLimit()
                    : config.getMaxFullMaceLimit();
            return filterByPrefix(Collections.singletonList(String.valueOf(currentMax)), args[2]);
        }
        return Collections.emptyList();
    }

    /**
     * Generic helper: returns completions for an ACTIVE UID argument at a given position.
     *
     * @param args     the full args array
     * @param argIndex the 0-based index of the UID argument within {@code args}
     * @return filtered list of active UIDs
     */
    private List<String> completeActiveUids(String[] args, int argIndex) {
        if (args.length == argIndex + 1) {
            return filterByPrefix(activeUids(), args[argIndex]);
        }
        return Collections.emptyList();
    }

    // =========================================================================
    // Data collection helpers
    // =========================================================================

    /**
     * Returns the UIDs of all ACTIVE maces from the registry.
     *
     * @return mutable list of active UID strings
     */
    private List<String> activeUids() {
        Collection<MaceEntry> all = registry.getAllEntries();
        List<String> result = new ArrayList<>(all.size());
        for (MaceEntry e : all) {
            if (e.getStatus() == MaceStatus.ACTIVE) {
                result.add(e.getUid());
            }
        }
        return result;
    }

    /**
     * Returns the UIDs of ALL maces in the registry, regardless of status.
     *
     * @return mutable list of all UID strings
     */
    private List<String> allUids() {
        Collection<MaceEntry> all = registry.getAllEntries();
        List<String> result = new ArrayList<>(all.size());
        for (MaceEntry e : all) {
            result.add(e.getUid());
        }
        return result;
    }

    /**
     * Returns the names of all currently online players.
     *
     * @return mutable list of online player names
     */
    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(HumanEntity::getName)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // =========================================================================
    // Filtering utility
    // =========================================================================

    /**
     * Filters a list of candidates so that only those whose lower-case representation
     * starts with the lower-case version of the partial input are retained.
     *
     * @param candidates the full list of possible completions
     * @param partial    the text the sender has typed so far (may be empty)
     * @return a new mutable list containing only the matching candidates
     */
    private List<String> filterByPrefix(List<String> candidates, String partial) {
        String lower = partial.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(lower)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
