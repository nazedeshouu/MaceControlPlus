package com.macecontrol.tracking;

import com.macecontrol.MaceControl;
import com.macecontrol.config.MaceConfig;
import com.macecontrol.data.MaceTier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;

/**
 * PDC read/write/verify helper for tracked maces.
 * <p>
 * Every tracked mace carries four PDC keys under the "macecontrol" namespace:
 * <ul>
 *   <li>{@code uid}        (STRING) — unique identifier, e.g. "MC-0001"</li>
 *   <li>{@code tier}       (STRING) — "LIMITED" or "FULL"</li>
 *   <li>{@code created_at} (LONG)   — epoch seconds</li>
 *   <li>{@code checksum}   (STRING) — HMAC-SHA256 of uid+"|"+tier+"|"+createdAt</li>
 * </ul>
 * This class does NOT implement Listener.
 */
public class MaceIdentifier {

    // -------------------------------------------------------------------------
    // PDC keys
    // -------------------------------------------------------------------------
    private final NamespacedKey keyUid;
    private final NamespacedKey keyTier;
    private final NamespacedKey keyCreatedAt;
    private final NamespacedKey keyChecksum;

    private final MaceControl plugin;
    private final MaceConfig config;

    /**
     * Constructs a new MaceIdentifier.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public MaceIdentifier(MaceControl plugin, MaceConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.keyUid       = new NamespacedKey("macecontrol", "uid");
        this.keyTier      = new NamespacedKey("macecontrol", "tier");
        this.keyCreatedAt = new NamespacedKey("macecontrol", "created_at");
        this.keyChecksum  = new NamespacedKey("macecontrol", "checksum");
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Stamps PDC tracking data onto an existing ItemStack.
     * The item must be a mace ({@link Material#MACE}).
     *
     * @param item      the ItemStack to stamp (modified in place)
     * @param uid       the unique identifier string, e.g. "MC-0001"
     * @param tier      the enchantment tier
     * @param createdAt epoch-second timestamp of creation
     * @throws IllegalArgumentException if item is null or not MACE
     */
    public void stampItem(ItemStack item, String uid, MaceTier tier, long createdAt) {
        if (item == null || item.getType() != Material.MACE) {
            throw new IllegalArgumentException("stampItem requires a non-null MACE ItemStack");
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("ItemMeta is null for MACE — this should never happen");
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyUid,       PersistentDataType.STRING, uid);
        pdc.set(keyTier,      PersistentDataType.STRING, tier.name());
        pdc.set(keyCreatedAt, PersistentDataType.LONG,   createdAt);

        String checksum = computeHmac(plugin.getHmacSecret(), buildPayload(uid, tier.name(), createdAt));
        pdc.set(keyChecksum, PersistentDataType.STRING, checksum);

        item.setItemMeta(meta);
    }

    /**
     * Reads the UID from an item's PDC.
     *
     * @param item the ItemStack to inspect
     * @return the UID string, or {@code null} if the key is absent or item has no meta
     */
    @Nullable
    public String getUid(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(keyUid, PersistentDataType.STRING);
    }

    /**
     * Reads the tier from an item's PDC.
     *
     * @param item the ItemStack to inspect
     * @return the {@link MaceTier}, or {@code null} if absent or unparseable
     */
    @Nullable
    public MaceTier getTier(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String tierStr = meta.getPersistentDataContainer().get(keyTier, PersistentDataType.STRING);
        if (tierStr == null) return null;
        try {
            return MaceTier.fromString(tierStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads the creation timestamp from an item's PDC.
     *
     * @param item the ItemStack to inspect
     * @return epoch-second creation time, or {@code -1} if absent
     */
    public long getCreatedAt(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return -1L;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return -1L;
        Long val = meta.getPersistentDataContainer().get(keyCreatedAt, PersistentDataType.LONG);
        return val != null ? val : -1L;
    }

    /**
     * Verifies the HMAC checksum stored in an item's PDC.
     * Returns {@code false} if any PDC field is missing or the checksum does not match.
     *
     * @param item the ItemStack to verify
     * @return {@code true} if the checksum is valid
     */
    public boolean verifyChecksum(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String uid       = pdc.get(keyUid,       PersistentDataType.STRING);
        String tierStr   = pdc.get(keyTier,       PersistentDataType.STRING);
        Long   createdAt = pdc.get(keyCreatedAt,  PersistentDataType.LONG);
        String stored    = pdc.get(keyChecksum,   PersistentDataType.STRING);

        if (uid == null || tierStr == null || createdAt == null || stored == null) return false;

        String expected = computeHmac(plugin.getHmacSecret(), buildPayload(uid, tierStr, createdAt));
        return expected.equals(stored);
    }

    /**
     * Applies lore to an item based on its tier and UID.
     * The config lore template's {@code %uid%} placeholder is replaced with the actual UID.
     *
     * @param item the ItemStack to update (modified in place)
     * @param tier the enchantment tier
     * @param uid  the unique identifier string
     */
    public void applyLore(ItemStack item, MaceTier tier, String uid) {
        if (item == null || item.getType() == Material.AIR) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<String> template = config.getLoreFormat(tier);
        List<String> lore = new ArrayList<>(template.size());
        for (String line : template) {
            lore.add(line.replace("%uid%", uid));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    /**
     * Re-applies lore from PDC data if the current lore has been altered or stripped.
     * This is a no-op if the item is not a tracked mace or lore already matches.
     *
     * @param item the ItemStack to fix (modified in place)
     */
    public void reapplyLoreIfNeeded(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return;
        String uid = getUid(item);
        if (uid == null) return;
        MaceTier tier = getTier(item);
        if (tier == null) return;

        // Build expected lore
        List<String> template = config.getLoreFormat(tier);
        List<String> expectedLore = new ArrayList<>(template.size());
        for (String line : template) {
            expectedLore.add(line.replace("%uid%", uid));
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<String> currentLore = meta.getLore();
        if (!expectedLore.equals(currentLore)) {
            meta.setLore(expectedLore);
            item.setItemMeta(meta);
        }
    }

    /**
     * Recomputes the HMAC checksum and writes the updated value back into the PDC.
     * Call this after changing a mace's tier (e.g. via {@code /mace settier}).
     *
     * @param item the ItemStack to update (modified in place)
     */
    public void recomputeChecksum(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String uid       = pdc.get(keyUid,       PersistentDataType.STRING);
        String tierStr   = pdc.get(keyTier,       PersistentDataType.STRING);
        Long   createdAt = pdc.get(keyCreatedAt,  PersistentDataType.LONG);

        if (uid == null || tierStr == null || createdAt == null) return;

        String newChecksum = computeHmac(plugin.getHmacSecret(), buildPayload(uid, tierStr, createdAt));
        pdc.set(keyChecksum, PersistentDataType.STRING, newChecksum);
        item.setItemMeta(meta);
    }

    /**
     * Creates a brand new tracked mace ItemStack with all PDC data and lore already applied.
     * The item is not yet registered in the registry — the caller must do that.
     *
     * @param uid  the unique identifier string
     * @param tier the enchantment tier
     * @return a fully stamped ItemStack of {@link Material#MACE} with quantity 1
     */
    public ItemStack createTrackedMace(String uid, MaceTier tier) {
        ItemStack item = new ItemStack(Material.MACE, 1);
        long createdAt = Instant.now().getEpochSecond();
        stampItem(item, uid, tier, createdAt);
        applyLore(item, tier, uid);
        return item;
    }

    /**
     * Returns {@code true} if the item is a MACE with no PDC uid (unregistered).
     *
     * @param item the ItemStack to check
     * @return {@code true} if Material is MACE and uid PDC key is absent
     */
    public boolean isUnregistered(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return false;
        return getUid(item) == null;
    }

    /**
     * Returns {@code true} if the item has a uid but a bad (or missing) checksum (tampered).
     *
     * @param item the ItemStack to check
     * @return {@code true} if uid is present but checksum verification fails
     */
    public boolean isTampered(ItemStack item) {
        if (item == null || item.getType() != Material.MACE) return false;
        String uid = getUid(item);
        if (uid == null) return false; // unregistered, not tampered
        return !verifyChecksum(item);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Builds the HMAC payload string from the three immutable mace fields.
     *
     * @param uid       the mace UID
     * @param tierStr   the tier name string
     * @param createdAt epoch-second creation timestamp
     * @return the pipe-delimited payload
     */
    private String buildPayload(String uid, String tierStr, long createdAt) {
        return uid + "|" + tierStr + "|" + createdAt;
    }

    /**
     * Computes an HMAC-SHA256 digest of the payload using the given secret.
     *
     * @param secret  the HMAC secret key (server-side, stored in internal-data.yml)
     * @param payload the string to sign
     * @return Base64-encoded HMAC digest
     * @throws RuntimeException if the JVM does not support HmacSHA256 or the key is invalid
     */
    private String computeHmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // HmacSHA256 is mandatory in all Java SE implementations — unreachable in practice
            throw new RuntimeException("HmacSHA256 not available", e);
        } catch (InvalidKeyException e) {
            plugin.getLogger().log(Level.SEVERE, "Invalid HMAC key — check internal-data.yml", e);
            throw new RuntimeException("Invalid HMAC key", e);
        }
    }
}
