package com.bannarak.zones;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.List;

/** One tracked activity, with the ladder of milestones hung off it. */
public class Mission {

    public static class Tier {
        public final long amount;
        public final double reward;
        public final List<String> commands;

        public Tier(long amount, double reward, List<String> commands) {
            this.amount = amount;
            this.reward = reward;
            this.commands = commands;
        }
    }

    public final String id;
    public final String display;
    public final Material icon;
    public final Statistic statistic;
    public final Material material;      // for block/item statistics
    public final EntityType entity;      // for entity statistics
    public final double scale;           // raw statistic units per displayed unit
    public final String unit;
    public final List<Tier> tiers;

    public Mission(String id, String display, Material icon, Statistic statistic,
                   Material material, EntityType entity, double scale, String unit, List<Tier> tiers) {
        this.id = id;
        this.display = display;
        this.icon = icon;
        this.statistic = statistic;
        this.material = material;
        this.entity = entity;
        this.scale = scale <= 0 ? 1 : scale;
        this.unit = unit;
        this.tiers = tiers;
    }

    /** Raw statistic value translated into the unit players see. */
    public long progress(Player p) {
        long raw;
        try {
            raw = switch (statistic.getType()) {
                case BLOCK, ITEM -> material == null ? 0 : p.getStatistic(statistic, material);
                case ENTITY -> entity == null ? 0 : p.getStatistic(statistic, entity);
                default -> p.getStatistic(statistic);
            };
        } catch (Exception ex) {
            return 0;
        }
        return (long) Math.floor(raw / scale);
    }

    public Tier tier(int index) {
        return index < 0 || index >= tiers.size() ? null : tiers.get(index);
    }

    public int tierCount() { return tiers.size(); }
}
