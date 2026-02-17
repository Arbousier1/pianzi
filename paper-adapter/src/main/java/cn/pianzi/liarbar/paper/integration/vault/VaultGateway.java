package cn.pianzi.liarbar.paper.integration.vault;

import java.util.UUID;

public interface VaultGateway {
    boolean withdraw(UUID playerId, double amount);

    void deposit(UUID playerId, double amount);
}
