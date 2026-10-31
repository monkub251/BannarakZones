package com.bannarak.zones;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Exposes our own money to any plugin that asks Vault for an economy. */
public class VaultBridge implements Economy {

    private final BannarakZones plugin;
    private final EconomyManager eco;

    public VaultBridge(BannarakZones plugin, EconomyManager eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    private UUID id(String name) {
        UUID found = eco.lookup(name);
        return found != null ? found : Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private static EconomyResponse ok(double balance, double amount) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private static EconomyResponse fail(double balance, String why) {
        return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, why);
    }

    private static EconomyResponse unsupported() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not used");
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "BannarakZones"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return eco.format(amount); }
    @Override public String currencyNamePlural() { return eco.currencyName(); }
    @Override public String currencyNameSingular() { return eco.currencyName(); }

    @Override public boolean hasAccount(String name) { return eco.hasAccount(id(name)); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p) { return eco.hasAccount(p.getUniqueId()); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public double getBalance(String name) { return eco.get(id(name)); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p) { return eco.get(p.getUniqueId()); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return eco.has(id(name), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, double amount) { return eco.has(p.getUniqueId(), amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override
    public EconomyResponse withdrawPlayer(String name, double amount) {
        return withdraw(id(name), amount);
    }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return withdraw(p.getUniqueId(), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdraw(p.getUniqueId(), amount); }

    private EconomyResponse withdraw(UUID uuid, double amount) {
        if (amount < 0) return fail(eco.get(uuid), "Negative amount");
        if (!eco.take(uuid, amount)) return fail(eco.get(uuid), "Insufficient funds");
        return ok(eco.get(uuid), amount);
    }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(id(name), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return deposit(p.getUniqueId(), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return deposit(p.getUniqueId(), amount); }

    private EconomyResponse deposit(UUID uuid, double amount) {
        if (amount < 0) return fail(eco.get(uuid), "Negative amount");
        eco.add(uuid, amount);
        return ok(eco.get(uuid), amount);
    }

    @Override public boolean createPlayerAccount(String name) { eco.createAccount(id(name), name); return true; }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { eco.createAccount(p.getUniqueId(), p.getName()); return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }

    // banks are not part of this server's design
    @Override public EconomyResponse createBank(String n, String p) { return unsupported(); }
    @Override public EconomyResponse createBank(String n, OfflinePlayer p) { return unsupported(); }
    @Override public EconomyResponse deleteBank(String n) { return unsupported(); }
    @Override public EconomyResponse bankBalance(String n) { return unsupported(); }
    @Override public EconomyResponse bankHas(String n, double a) { return unsupported(); }
    @Override public EconomyResponse bankWithdraw(String n, double a) { return unsupported(); }
    @Override public EconomyResponse bankDeposit(String n, double a) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String n, String p) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String n, OfflinePlayer p) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String n, String p) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String n, OfflinePlayer p) { return unsupported(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
