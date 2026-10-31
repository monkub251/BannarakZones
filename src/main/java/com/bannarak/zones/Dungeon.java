package com.bannarak.zones;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.*;

/** One live run of the dungeon. */
public class Dungeon {

    public enum State { COUNTDOWN, FIGHTING, BREAK, FINISHED }

    public final UUID id = UUID.randomUUID();
    public final UUID leader;
    public final Set<UUID> party = new LinkedHashSet<>();
    public final Set<UUID> alive = new LinkedHashSet<>();
    public final Set<UUID> mobs = new HashSet<>();
    public int wave = 0;
    public State state = State.COUNTDOWN;
    public BossBar bar;
    public LivingEntity boss;
    public long startedAt = System.currentTimeMillis();
    public double earned = 0;

    public Dungeon(UUID leader, Collection<UUID> members) {
        this.leader = leader;
        party.addAll(members);
        alive.addAll(members);
    }

    public List<Player> onlineParty() {
        List<Player> list = new ArrayList<>();
        for (UUID u : party) {
            Player p = org.bukkit.Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }

    public List<Player> onlineAlive() {
        List<Player> list = new ArrayList<>();
        for (UUID u : alive) {
            Player p = org.bukkit.Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }
}
