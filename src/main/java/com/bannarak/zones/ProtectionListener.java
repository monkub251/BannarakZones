package com.bannarak.zones;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.InventoryHolder;

import java.util.Iterator;
import java.util.UUID;

public class ProtectionListener implements Listener {

    private final BannarakZones plugin;

    public ProtectionListener(BannarakZones plugin) {
        this.plugin = plugin;
    }

    /** True for a hostile mob, or for something a hostile mob threw. */
    private boolean hostile(Entity damager) {
        if (damager instanceof Monster || damager instanceof Slime
                || damager instanceof Phantom || damager instanceof Hoglin) return true;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter)
            return hostile(shooter);
        return damager instanceof Explosive || damager instanceof AreaEffectCloud;
    }

    /** Where mobs are not allowed to hurt anyone. */
    private boolean safeFromMobs(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (plugin.worldLocked(loc.getWorld().getName())) return true;
        if (plugin.safeWorld(loc.getWorld().getName())) return true;
        Zone z = plugin.store().zoneAt(loc);
        return z != null && !z.allowMobDamage;
    }

    private boolean denied(Player player, Location loc) {
        if (player != null && plugin.canBypass(player)) return false;
        // A locked world is staff-only ground, zone or no zone. This is what
        // keeps the lobby standing.
        if (loc != null && loc.getWorld() != null
                && plugin.worldLocked(loc.getWorld().getName())) return true;
        Zone z = plugin.store().zoneAt(loc);
        if (z == null) return false;
        return player == null || !z.isTrusted(player.getUniqueId());
    }

    /** The label to show for whoever owns the ground here. */
    private String ownerLabel(Location loc) {
        if (loc != null && loc.getWorld() != null
                && plugin.worldLocked(loc.getWorld().getName())) return "ทีมงาน";
        Zone z = zoneAt(loc);
        if (z == null) return "?";
        return z.admin ? "ทีมงาน" : z.ownerName;
    }

    private Zone zoneAt(Location loc) {
        return plugin.store().zoneAt(loc);
    }

    // ---------- creating / removing a zone ----------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        var held = e.getItemInHand();
        if (b.getType() == plugin.adminBlock() && p.hasPermission("zones.admin")
                && plugin.blocks().triggers(held, ServerBlocks.Kind.ADMIN)) {
            int radius = plugin.adminRadius();
            Zone zone = new Zone(UUID.randomUUID(), p.getUniqueId(), p.getName(),
                    b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), radius);
            zone.admin = true;
            zone.showMessages = false;
            plugin.store().add(zone);
            plugin.store().save();
            Notify.popup(p, "<gold>เขตทีมงาน", "<gray>รัศมี " + radius + " บล็อก",
                    "block.beacon.activate", 0.8f);
            Notify.chat(p, "<gray>ทุบบล็อกนี้เพื่อลบเขต · <white>/zone <gray>เพื่อตั้งค่า");
            plugin.showBorder(p, zone);
            return;
        }

        if (b.getType() == plugin.zoneBlock()
                && plugin.blocks().triggers(held, ServerBlocks.Kind.ZONE)) {
            if (!p.hasPermission("zones.use")) return;
            if (plugin.worldBlocked(b.getWorld().getName())) {
                plugin.msg(p, "blocked-world");
                e.setCancelled(true);
                return;
            }
            UUID id = p.getUniqueId();
            int owned = plugin.store().zonesOf(id).size();
            int slots = plugin.store().slotsOf(id);
            if (owned >= slots && !plugin.canBypass(p)) {
                plugin.msg(p, "no-slots", BannarakZones.p("price", BannarakZones.money(plugin.slotPrice(slots))));
                e.setCancelled(true);
                return;
            }
            int radius = plugin.defaultRadius();
            if (plugin.store().wouldOverlap(b.getWorld().getName(), b.getX(), b.getZ(), radius, null, plugin.minGap())) {
                plugin.msg(p, "overlap");
                e.setCancelled(true);
                return;
            }
            Zone zone = new Zone(UUID.randomUUID(), id, p.getName(), b.getWorld().getName(),
                    b.getX(), b.getY(), b.getZ(), radius);
            plugin.store().add(zone);
            plugin.store().save();
            plugin.msg(p, "created", BannarakZones.p("radius", String.valueOf(radius)));
            plugin.showBorder(p, zone);
            plugin.playSound(p, "block.beacon.activate", 1.2f);
            return;
        }

        if (plugin.protect("block-place") && denied(p, b.getLocation())) {
            plugin.msg(p, "deny-build", BannarakZones.p("owner", ownerLabel(b.getLocation())));
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        Zone anchor = plugin.store().zoneByBeacon(b.getLocation());
        if (anchor != null) {
            if (anchor.admin && !p.hasPermission("zones.admin")) {
                Notify.chat(p, "<red>บล็อกนี้เป็นของทีมงาน");
                e.setCancelled(true);
                return;
            }
            if (!anchor.owner.equals(p.getUniqueId()) && !plugin.canBypass(p)) {
                plugin.msg(p, "not-owner");
                e.setCancelled(true);
                return;
            }
            plugin.store().remove(anchor);
            plugin.store().save();
            // hand back the tagged block, not the plain one vanilla would drop
            if (p.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                e.setDropItems(false);
                b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5),
                        plugin.blocks().make(anchor.admin
                                ? ServerBlocks.Kind.ADMIN : ServerBlocks.Kind.ZONE, 1));
            }
            plugin.msg(p, "removed");
            plugin.playSound(p, "block.beacon.deactivate", 0.8f);
            return;
        }

        if (plugin.protect("block-break") && denied(p, b.getLocation())) {
            plugin.msg(p, "deny-build", BannarakZones.p("owner", ownerLabel(b.getLocation())));
            e.setCancelled(true);
        }
    }

    // ---------- menu + containers ----------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();

        Zone anchor = plugin.store().zoneByBeacon(b.getLocation());
        if (anchor != null) {
            e.setCancelled(true);
            if (anchor.isTrusted(p.getUniqueId()) || plugin.canBypass(p)) {
                plugin.gui().openMain(p, anchor);
            } else {
                plugin.msg(p, "deny-build", BannarakZones.p("owner", anchor.ownerName));
            }
            return;
        }

        if (!plugin.protect("containers")) return;
        if (!isProtectedInteract(b)) return;
        if (denied(p, b.getLocation())) {
            Zone z = zoneAt(b.getLocation());
            plugin.msg(p, "deny-container", BannarakZones.p("owner", z == null ? "?" : z.ownerName));
            e.setCancelled(true);
        }
    }

    private boolean isProtectedInteract(Block b) {
        BlockState state = b.getState();
        if (state instanceof InventoryHolder) return true;
        Material t = b.getType();
        String n = t.name();
        return n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") || n.endsWith("_FENCE_GATE")
                || n.endsWith("_BUTTON") || n.endsWith("_SIGN") || n.endsWith("_BED")
                || t == Material.LEVER || t == Material.ANVIL || t == Material.CHIPPED_ANVIL
                || t == Material.DAMAGED_ANVIL || t == Material.ENCHANTING_TABLE
                || t == Material.CRAFTING_TABLE || t == Material.CAKE
                || t == Material.REPEATER || t == Material.COMPARATOR
                || t == Material.NOTE_BLOCK || t == Material.JUKEBOX
                || t == Material.RESPAWN_ANCHOR || t == Material.FLOWER_POT;
    }

    // ---------- explosions, fire, fluids ----------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.protect("explosions")) return;
        filterBlocks(e.blockList().iterator());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!plugin.protect("explosions")) return;
        filterBlocks(e.blockList().iterator());
    }

    private void filterBlocks(Iterator<Block> it) {
        while (it.hasNext()) {
            Block b = it.next();
            if (plugin.worldLocked(b.getWorld().getName())) { it.remove(); continue; }
            Zone z = plugin.store().columnAt(b.getWorld().getName(), b.getX(), b.getZ());
            if (z != null && (z.admin || !z.allowExplosions)) it.remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!plugin.protect("fire")) return;
        Block b = e.getBlock();
        Player p = e.getPlayer();
        if (p != null && plugin.canBypass(p)) return;
        if (plugin.worldLocked(b.getWorld().getName())) { e.setCancelled(true); return; }
        Zone z = plugin.store().columnAt(b.getWorld().getName(), b.getX(), b.getZ());
        if (z == null) return;
        if (p != null && z.isTrusted(p.getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!plugin.protect("fire")) return;
        Block b = e.getBlock();
        if (plugin.worldLocked(b.getWorld().getName())
                || plugin.store().columnAt(b.getWorld().getName(), b.getX(), b.getZ()) != null)
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (!plugin.protect("fire")) return;
        if (e.getSource().getType() != Material.FIRE) return;
        Block b = e.getBlock();
        if (plugin.worldLocked(b.getWorld().getName())
                || plugin.store().columnAt(b.getWorld().getName(), b.getX(), b.getZ()) != null)
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!plugin.protect("buckets")) return;
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!plugin.protect("buckets")) return;
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Block to = e.getToBlock();
        Zone target = zoneAt(to.getLocation());
        if (target == null) return;
        Zone from = zoneAt(e.getBlock().getLocation());
        if (from == null || !from.id.equals(target.id)) e.setCancelled(true);
    }

    // ---------- pistons ----------

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!plugin.protect("pistons")) return;
        if (pistonBlocked(e.getBlock().getLocation(), e.getBlocks())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!plugin.protect("pistons")) return;
        if (pistonBlocked(e.getBlock().getLocation(), e.getBlocks())) e.setCancelled(true);
    }

    private boolean pistonBlocked(Location piston, java.util.List<Block> moved) {
        Zone origin = zoneAt(piston);
        for (Block b : moved) {
            // never let a piston shove the anchor block out of place
            if (plugin.store().zoneByBeacon(b.getLocation()) != null) return true;
            Zone z = zoneAt(b.getLocation());
            if (z != null && (origin == null || !origin.id.equals(z.id))) return true;
        }
        return false;
    }

    // ---------- entities and pvp ----------

    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();

        // Hostile mobs first: a safe world or a safe zone means a player
        // takes nothing from them, whether it is a claw or an arrow.
        if (victim instanceof Player hurt && hostile(e.getDamager()) && safeFromMobs(hurt.getLocation())) {
            e.setCancelled(true);
            return;
        }

        Zone z = zoneAt(victim.getLocation());
        if (z == null) return;
        Player att = attacker(e.getDamager());

        if (victim instanceof Player) {
            if (!plugin.protect("pvp") || z.allowPvp) return;
            if (att == null) return;
            if (plugin.canBypass(att)) return;
            e.setCancelled(true);
            plugin.msg(att, "deny-pvp");
            return;
        }

        if (!plugin.protect("entities")) return;
        if (victim instanceof Monster) return;
        if (att == null) { e.setCancelled(true); return; }
        if (plugin.canBypass(att) || z.isTrusted(att.getUniqueId())) return;
        e.setCancelled(true);
        plugin.msg(att, "deny-build", BannarakZones.p("owner", z.ownerName));
    }

    @EventHandler(ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        if (!plugin.protect("entities")) return;
        Zone z = zoneAt(e.getEntity().getLocation());
        if (z == null) return;
        Player att = attacker(e.getRemover());
        if (att != null && (plugin.canBypass(att) || z.isTrusted(att.getUniqueId()))) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!plugin.protect("entities")) return;
        Entity ent = e.getRightClicked();
        if (ent instanceof Monster) return;
        if (denied(e.getPlayer(), ent.getLocation())) {
            Zone z = zoneAt(ent.getLocation());
            plugin.msg(e.getPlayer(), "deny-build", BannarakZones.p("owner", z == null ? "?" : z.ownerName));
            e.setCancelled(true);
        }
    }
}
