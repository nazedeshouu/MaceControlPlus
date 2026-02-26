# MaceControl+

**Adaptive mace tracking & management plugin for Paper 1.21.10+**

> Created by **nazedeshou**

MaceControl treats every mace on your server as a uniquely identified, tier-classified asset. It enforces independent per-tier caps on how many standard (LIMITED) and legendary (FULL) maces can exist at once, controls which enchantments each mace can receive, detects and removes duplicates, and gives admins full visibility into where every mace is at all times.

---

## Why This Plugin Exists

Trial chambers made maces farmable. On a competitive survival server, unlimited maces with Breach + Density + Wind Burst trivialize PvP balance. MaceControl lets you run a scarcity economy: decide exactly how many maces your server has, which ones get full enchantments, and track them as they change hands through normal survival gameplay.

Maces are **not** bound to players. Anyone can pick up, use, trade, or store a mace. The plugin tracks *where the mace is*, not *whose it is*.

---

## Features

### Mace Tracking (Three Layers)

- **PersistentDataContainer (PDC):** Every tracked mace carries an invisible, unforgeable UID stamped into its item metadata. Survives anvils, hoppers, ender chests, and server restarts. Includes an HMAC-SHA256 checksum computed with a server-side secret to prevent forgery.
- **Lore:** A human-readable tag (e.g. `Mace #MC-0001` or `Legendary Mace #MC-0005`) for quick visual identification. Not authoritative — the PDC is the source of truth.
- **SQLite Registry:** Server-side database tracks every mace's status, location, scan history, and full audit trail.

### Slot System

Each tier has its own independent slot limit: `max-normal-mace-limit` controls how many LIMITED (standard) maces can exist, and `max-full-mace-limit` controls how many FULL (legendary) maces can exist. Players can craft maces normally at a crafting table until all LIMITED slots are filled.

When a player crafts a mace or an admin issues one, the entire server is notified:
```
⚔ Steve has obtained a mace! (3/4 standard | 1/1 legendary)
```

### Enchantment Tiers

| Tier | Allowed Enchantments | How Obtained |
|------|---------------------|--------------|
| **LIMITED** (Standard) | Wind Burst, Mending (anvil only), Unbreaking | Player crafting |
| **FULL** (Legendary) | All mace enchantments | Admin command only |

Forbidden enchantments are silently stripped at the enchanting table and blocked at the anvil.

### Real-Time Movement Tracking

Every time a mace moves — picked up, dropped, placed in a chest, pushed by a hopper, ejected by a dispenser, dropped on death — the plugin updates its known location instantly via over 20 monitored Bukkit events.

### Periodic Deep Scan

Every 2–6 hours (randomized, configurable), the plugin runs a full scan across all online player inventories, containers in loaded chunks, shulker boxes (recursive), item entities, item frames, and optionally offline player data files.

### Zero-Tolerance Unregistered Mace Policy

Any mace without valid tracking data is deleted immediately, no matter who holds it. The only way a mace enters the system is by crafting one or via `/mace give`.

### Anti-Duplication

- Unregistered maces (no PDC data) are deleted unconditionally on encounter
- Tampered maces (invalid HMAC checksum) are deleted on encounter
- Duplicate UIDs trigger deletion of the copy, preserving the original

### Audit Trail

Every mace event is logged to the database with timestamp, event type, involved player, and coordinates. Queryable via `/mace audit`.

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/mace` | — | Public: shows maces in circulation and available slots |
| `/mace list` | `macecontrol.admin` | List all registered maces with status |
| `/mace locate [uid\|all]` | `macecontrol.admin` | Show mace locations |
| `/mace give <player> <tier>` | `macecontrol.admin` | Create and give a new tracked mace |
| `/mace revoke <uid>` | `macecontrol.admin` | Remove a mace from the world by UID |
| `/mace return <uid>` | `macecontrol.admin` | Teleport a mace to your inventory |
| `/mace settier <uid> <tier>` | `macecontrol.admin` | Change a mace's enchantment tier |
| `/mace setslots <tier> <n>` | `macecontrol.admin` | Change the max maces allowed for a tier |
| `/mace scan` | `macecontrol.admin` | Force an immediate full scan |
| `/mace info` | `macecontrol.admin` | Inspect the mace you're holding |
| `/mace audit <uid>` | `macecontrol.admin` | View movement history for a mace |
| `/mace reload` | `macecontrol.admin` | Reload config from disk |

---

## Installation

1. Download `MaceControlPlus-1.0.0.jar` from the [Releases](../../releases) page (or find it in `target/` if building from source).
2. Place it in your server's `plugins/` directory.
3. Start or restart the server.
4. Edit `plugins/MaceControl+/config.yml` to your liking.
5. Run `/mace reload` to apply changes, or restart.

> **Important:** On first launch, any pre-existing vanilla maces on the server will be **automatically deleted** on their first encounter because they lack tracking data. Use `/mace give` to issue new tracked maces, or let players craft them through the slot system.

---

## Requirements

- **Paper 1.21.10+** (or any Paper fork such as Purpur)
- **Java 21** (the JAR is compiled for Java 21 — older versions will not load it)
- No other plugin dependencies

> **Note:** Spigot is not supported. The plugin uses Paper-specific APIs.

---

## Configuration

```yaml
# Independent slot limits per tier
max-normal-mace-limit: 4
max-full-mace-limit: 1

# Scan timing (random interval in this range, in minutes)
scan:
  interval-min-minutes: 120
  interval-max-minutes: 360
  missed-scans-to-destroy: 4

# Per-tier enchantment whitelists
tiers:
  LIMITED:
    allowed-enchantments:
      - WIND_BURST
      - UNBREAKING
      - MENDING
  FULL:
    allowed-enchantments: ALL

# Public announcement when a mace is crafted or given
notifications:
  public-craft-message: "§6⚔ {player} has obtained a mace! §7({normal_count}/{normal_max} standard §8| §b{full_count}/{full_max} legendary§7)"
```

---

## Permissions

| Permission | Default | Description |
|-----------|---------|-------------|
| `macecontrol.admin` | OP | Access to all admin subcommands |

The base `/mace` command (showing public slot counts) requires no permission.

---

## Building From Source

```bash
git clone [https://github.com/nazedeshouu/MaceControlPlus.git]
cd MaceControl
mvn clean package
```

The compiled JAR will be at `target/MaceControlPlus-1.0.0.jar`. Requires Maven and **Java 21**.

---

## Data Storage

- **Database:** `plugins/MaceControl+/macecontrol.db` (SQLite)
- **Tables:** `maces` (registry), `mace_audit_log` (event history)
- **Performance:** All reads use an in-memory cache. Database writes are async. No main thread blocking.

---

## License

[MIT](LICENSE)

---

## Credits

Created by **nazedeshou**

---

## FAQ

**Q: What happens to existing maces when I first install the plugin?**
A: They are deleted on first encounter. Use `/mace give` to issue fresh tracked maces.

**Q: I'm an admin/OP and my mace got deleted!**
A: There are no exemptions. Give yourself a new one with `/mace give <your_name> LIMITED` (or `FULL`).

**Q: What if someone uses a mod/client to edit NBT?**
A: The HMAC checksum catches this. Any item with a valid UID but invalid checksum is flagged as tampered and deleted.

**Q: What happens if a player renames a mace on an anvil?**
A: PDC data survives anvil renames. If the lore is altered, the plugin re-applies it on the next event encounter.

**Q: Does this work in the Nether and End?**
A: Yes. All worlds returned by `Bukkit.getWorlds()` are scanned.
