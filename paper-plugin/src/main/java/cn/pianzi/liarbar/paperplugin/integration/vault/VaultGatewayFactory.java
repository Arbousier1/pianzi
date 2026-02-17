package cn.pianzi.liarbar.paperplugin.integration.vault;

import cn.pianzi.liarbar.paper.integration.vault.VaultGateway;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class VaultGatewayFactory {
    private static final VaultGateway DISABLED_GATEWAY = new VaultGateway() {
        @Override
        public boolean withdraw(java.util.UUID playerId, double amount) {
            return false;
        }

        @Override
        public void deposit(java.util.UUID playerId, double amount) {
        }
    };

    private VaultGatewayFactory() {
    }

    public static Optional<VaultGateway> fromServer(JavaPlugin plugin) {
        RegisteredServiceProvider<Economy> registration = plugin.getServer()
                .getServicesManager()
                .getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            return Optional.empty();
        }
        return Optional.of(new BukkitVaultGateway(registration.getProvider()));
    }

    public static VaultGateway disabledGateway() {
        return DISABLED_GATEWAY;
    }
}
