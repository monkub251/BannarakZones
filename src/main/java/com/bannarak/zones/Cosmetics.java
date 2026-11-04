package com.bannarak.zones;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * What points actually buy: a name colour and a pet that trots after you.
 *
 * Both are looks and nothing else. The pet cannot fight, cannot be hit, drops
 * nothing and carries nothing - if it could do any of those it would be an
 * advantage somebody paid for, which is the thing we are not doing.
 */
public class Cosmetics implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;
    /** what each player owns and what they have switched on */
    private final Map<UUID, Set<String>> ownedColours = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> ownedPets = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeColour = new ConcurrentHashMap<>();
    private final Map<UUID, String> activePet = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> ownedTitles = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeTitle = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> livePet = new ConcurrentHashMap<>();
    private boolean dirty = false;

    public Cosmetics(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cosmetics.yml");
    }

    // ---------------- ownership ----------------

    public boolean ownsColour(UUID id, String key) {
        return ownedColours.getOrDefault(id, Set.of()).contains(key);
    }

    public boolean ownsPet(UUID id, String key) {
        return ownedPets.getOrDefault(id, Set.of()).contains(key);
    }

    public void grantColour(UUID id, String key) {
        ownedColours.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(key);
        dirty = true;
    }

    public void grantPet(UUID id, String key) {
        ownedPets.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(key);
        dirty = true;
    }

    public boolean ownsTitle(UUID id, String key) {
        return ownedTitles.getOrDefault(id, Set.of()).contains(key);
    }

    public void grantTitle(UUID id, String key) {
        ownedTitles.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    public Set<String> titles(UUID id) { return ownedTitles.getOrDefault(id, Set.of()); }

    public String activeTitleKey(UUID id) { return activeTitle.get(id); }

    /**
     * The tag that goes in front of a name in chat, bought with points.
     *
     * <p>Empty when they have none equipped, which is the normal case — the
     * rank prefix then shows on its own, as it always did.
     */
    public String titleTag(UUID id) {
        String key = activeTitle.get(id);
        if (key == null) return "";
        var s = plugin.getConfig().getConfigurationSection("cosmetics.titles." + key);
        if (s == null) return "";
        String tag = s.getString("prefix", "");
        return tag == null ? "" : tag;
    }

    public void setTitle(Player p, String key) {
        if (key == null || key.isEmpty()) {
            activeTitle.remove(p.getUniqueId());
            Notify.bar(p, "<gray>เอายศพิเศษออกแล้ว", "ui.button.click", 1f);
            return;
        }
        if (!ownsTitle(p.getUniqueId(), key)) {
            Notify.chat(p, "<red>คุณยังไม่มียศนี้");
            return;
        }
        activeTitle.put(p.getUniqueId(), key);
        Notify.bar(p, "<green>ใส่ยศพิเศษแล้ว", "entity.player.levelup", 1.4f);
    }

    public Set<String> colours(UUID id) { return ownedColours.getOrDefault(id, Set.of()); }
    public Set<String> pets(UUID id) { return ownedPets.getOrDefault(id, Set.of()); }

    // ---------------- name colour ----------------

    /** The MiniMessage wrapper for this player's chosen colour, or null for plain. */
    public String colourTag(UUID id) {
        String key = activeColour.get(id);
        if (key == null) return null;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("cosmetics.colours." + key);
        return s == null ? null : s.getString("tag");
    }

    /** Wraps a name in the player's colour. Falls back to the plain name. */
    public String paint(UUID id, String name) {
        String tag = colourTag(id);
        if (tag == null) return name;
        return tag + name + closing(tag);
    }

    /** Builds the matching closing tag for a simple opening tag. */
    private String closing(String tag) {
        if (!tag.startsWith("<") || !tag.endsWith(">")) return "";
        String inner = tag.substring(1, tag.length() - 1);
        int colon = inner.indexOf(':');
        String head = colon < 0 ? inner : inner.substring(0, colon);
        return "</" + head + ">";
    }

    public void setColour(Player p, String key) {
        if (key == null) { activeColour.remove(p.getUniqueId()); dirty = true;
            Notify.bar(p, "<gray>เอาสีชื่อออกแล้ว", null, 1f); return; }
        if (!ownsColour(p.getUniqueId(), key)) { Notify.chat(p, "<red>คุณยังไม่มีสีนี้"); return; }
        activeColour.put(p.getUniqueId(), key);
        dirty = true;
        Notify.bar(p, "<green>เปลี่ยนสีชื่อแล้ว", "block.note_block.pling", 1.4f);
    }

    public String activeColourKey(UUID id) { return activeColour.get(id); }
    public String activePetKey(UUID id) { return activePet.get(id); }

    // ---------------- pet ----------------

    public void setPet(Player p, String key) {
        despawnPet(p.getUniqueId());
        if (key == null) { activePet.remove(p.getUniqueId()); dirty = true;
            Notify.bar(p, "<gray>เก็บสัตว์เลี้ยงแล้ว", null, 1f); return; }
        if (!ownsPet(p.getUniqueId(), key)) { Notify.chat(p, "<red>คุณยังไม่มีสัตว์เลี้ยงตัวนี้"); return; }
        activePet.put(p.getUniqueId(), key);
        dirty = true;
        spawnPet(p);
        Notify.bar(p, "<green>เรียกสัตว์เลี้ยงออกมาแล้ว", "entity.cat.purr", 1.2f);
    }

    private void spawnPet(Player p) {
        String key = activePet.get(p.getUniqueId());
        if (key == null) return;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("cosmetics.pets." + key);
        if (s == null) return;
        EntityType type;
        try { type = EntityType.valueOf(s.getString("entity", "CAT").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Pet " + key + ": unknown entity, skipped");
            return;
        }
        Location at = p.getLocation().clone().add(1, 0, 1);
        Entity e;
        try { e = at.getWorld().spawnEntity(at, type); }
        catch (Throwable t) { plugin.getLogger().warning("Pet " + key + " failed: " + t.getMessage()); return; }

        e.setInvulnerable(true);
        e.setSilent(true);
        e.setPersistent(false);
        e.customName(MM.deserialize(s.getString("name", "<yellow>สัตว์เลี้ยง")
                .replace("<owner>", p.getName())));
        e.setCustomNameVisible(true);
        if (e instanceof LivingEntity le) {
            le.setAI(false);          // it is decoration, it does not act
            le.setCollidable(false);
            le.setRemoveWhenFarAway(true);
            try { le.setCanPickupItems(false); } catch (Throwable ignored) { }
        }
        livePet.put(p.getUniqueId(), e.getUniqueId());
    }

    public void despawnPet(UUID owner) {
        UUID u = livePet.remove(owner);
        if (u == null) return;
        Entity e = Bukkit.getEntity(u);
        if (e != null) e.remove();
    }

    /** Called on a timer: keeps each pet trotting along beside its owner. */
    public void followTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String key = activePet.get(p.getUniqueId());
            if (key == null) continue;
            UUID u = livePet.get(p.getUniqueId());
            Entity e = u == null ? null : Bukkit.getEntity(u);
            if (e == null || e.isDead() || !e.isValid()) { spawnPet(p); continue; }

            Location want = p.getLocation().clone()
                    .add(p.getLocation().getDirection().multiply(-1.4))
                    .add(0.8, 0, 0);
            if (!Objects.equals(e.getWorld(), p.getWorld())
                    || e.getLocation().distanceSquared(p.getLocation()) > 4) {
                want.setYaw(p.getLocation().getYaw());
                e.teleport(want);
            }
        }
    }

    // ---------------- events ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline() && activePet.containsKey(e.getPlayer().getUniqueId()))
                spawnPet(e.getPlayer());
        }, 30L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        despawnPet(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPetDamage(EntityDamageEvent e) {
        if (livePet.containsValue(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(livePet.keySet())) despawnPet(id);
        save(true);
    }

    // ---------------- storage ----------------

    public void load() {
        ownedColours.clear(); ownedPets.clear(); activeColour.clear(); activePet.clear();
        ownedTitles.clear(); activeTitle.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("players");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                ConfigurationSection s = root.getConfigurationSection(k);
                if (s == null) continue;
                ownedColours.put(id, new LinkedHashSet<>(s.getStringList("colours")));
                ownedPets.put(id, new LinkedHashSet<>(s.getStringList("pets")));
                ownedTitles.put(id, new LinkedHashSet<>(s.getStringList("titles")));
                if (s.isString("active-colour")) activeColour.put(id, s.getString("active-colour"));
                if (s.isString("active-pet")) activePet.put(id, s.getString("active-pet"));
                if (s.isString("active-title")) activeTitle.put(id, s.getString("active-title"));
            } catch (Exception ignored) { }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        Set<UUID> all = new HashSet<>();
        all.addAll(ownedColours.keySet()); all.addAll(ownedPets.keySet());
        all.addAll(activeColour.keySet()); all.addAll(activePet.keySet());
        all.addAll(ownedTitles.keySet()); all.addAll(activeTitle.keySet());
        for (UUID id : all) {
            String p = "players." + id;
            cfg.set(p + ".colours", new ArrayList<>(colours(id)));
            cfg.set(p + ".pets", new ArrayList<>(pets(id)));
            cfg.set(p + ".titles", new ArrayList<>(titles(id)));
            if (activeColour.containsKey(id)) cfg.set(p + ".active-colour", activeColour.get(id));
            if (activePet.containsKey(id)) cfg.set(p + ".active-pet", activePet.get(id));
            if (activeTitle.containsKey(id)) cfg.set(p + ".active-title", activeTitle.get(id));
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save cosmetics.yml", ex);
        }
    }
}
