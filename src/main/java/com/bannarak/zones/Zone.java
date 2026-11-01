package com.bannarak.zones;

import org.bukkit.Location;
import java.util.*;

public class Zone {
    public final UUID id;
    public UUID owner;
    public String ownerName;
    public final String world;
    public final int x, y, z;
    public int radius;
    public final Set<UUID> members = new LinkedHashSet<>();
    public final Map<UUID, String> memberNames = new LinkedHashMap<>();
    public boolean allowPvp = false;
    public boolean allowExplosions = false;
    public boolean showMessages = true;

    /** A staff zone. Nobody but staff gets through it, members and all. */
    public boolean admin = false;

    /** When false, nothing hostile may lay a finger on a player in here. */
    public boolean allowMobDamage = false;

    public Zone(UUID id, UUID owner, String ownerName, String world, int x, int y, int z, int radius) {
        this.id = id; this.owner = owner; this.ownerName = ownerName;
        this.world = world; this.x = x; this.y = y; this.z = z; this.radius = radius;
    }

    public boolean coversColumn(String w, int bx, int bz) {
        return world.equals(w) && Math.abs(bx - x) <= radius && Math.abs(bz - z) <= radius;
    }

    public boolean contains(String w, int bx, int by, int bz, boolean fullHeight, int vRange) {
        if (!coversColumn(w, bx, bz)) return false;
        return fullHeight || Math.abs(by - y) <= vRange;
    }

    public boolean contains(Location loc, boolean fullHeight, int vRange) {
        if (loc == null || loc.getWorld() == null) return false;
        return contains(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), fullHeight, vRange);
    }

    public boolean isTrusted(UUID uuid) {
        // A staff zone trusts nobody by name. Staff come through on their
        // permission instead, so there is no list to keep in sync.
        if (admin) return false;
        return owner.equals(uuid) || members.contains(uuid);
    }

    public void addMember(UUID uuid, String name) {
        members.add(uuid);
        memberNames.put(uuid, name);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
        memberNames.remove(uuid);
    }

    public String memberName(UUID uuid) {
        return memberNames.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }
}
