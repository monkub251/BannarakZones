package com.bannarak.zones;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MoveListener implements Listener {

    private final BannarakZones plugin;
    private final Map<UUID, UUID> inside = new HashMap<>();

    public MoveListener(BannarakZones plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        check(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        plugin.getServer().getScheduler().runTask(plugin, () -> check(e.getPlayer()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        check(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inside.remove(e.getPlayer().getUniqueId());
    }

    private void check(Player p) {
        Zone now = plugin.store().zoneAt(p.getLocation());
        UUID nowId = now == null ? null : now.id;
        UUID wasId = inside.get(p.getUniqueId());
        if (java.util.Objects.equals(nowId, wasId)) return;

        if (wasId != null) {
            Zone old = null;
            for (Zone z : plugin.store().all()) if (z.id.equals(wasId)) { old = z; break; }
            if (old != null && old.showMessages) {
                boolean own = old.owner.equals(p.getUniqueId());
                p.sendActionBar(plugin.effect(own ? "leave-own" : "leave",
                        BannarakZones.p("owner", old.ownerName)));
                plugin.playSound(p, plugin.getConfig().getString("effects.sound-leave", "block.note_block.bass"),
                        (float) plugin.getConfig().getDouble("effects.sound-pitch-leave", 0.8));
            }
        }

        if (now != null && now.showMessages) {
            boolean own = now.owner.equals(p.getUniqueId());
            p.sendActionBar(plugin.effect(own ? "enter-own" : "enter",
                    BannarakZones.p("owner", now.ownerName)));
            plugin.playSound(p, plugin.getConfig().getString("effects.sound-enter", "block.note_block.pling"),
                    (float) plugin.getConfig().getDouble("effects.sound-pitch-enter", 1.6));
        }

        if (nowId == null) inside.remove(p.getUniqueId());
        else inside.put(p.getUniqueId(), nowId);
    }
}
