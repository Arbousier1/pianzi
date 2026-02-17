package cn.pianzi.liarbar.paperplugin.integration.vault;

import cn.pianzi.liarbar.paper.integration.vault.VaultGateway;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;

import java.util.Objects;
import java.util.UUID;

public final class BukkitVaultGateway implements VaultGateway {
    private final Economy economy;

    public BukkitVaultGateway(Economy economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public boolean withdraw(UUID playerId, double amount) {
        if (amount <= 0.0D) {
            return true;
        }
        EconomyResponse response = economy.withdrawPlayer(Bukkit.getOfflinePlayer(playerId), amount);
        return response.transactionSuccess();
    }

    @Override
    public void deposit(UUID playerId, double amount) {
        if (amount <= 0.0D) {
            return;
        }
        economy.depositPlayer(Bukkit.getOfflinePlayer(playerId), amount);
    }
}
