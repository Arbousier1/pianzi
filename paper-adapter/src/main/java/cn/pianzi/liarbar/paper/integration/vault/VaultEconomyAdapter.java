package cn.pianzi.liarbar.paper.integration.vault;

import cn.pianzi.liarbar.core.domain.TableMode;
import cn.pianzi.liarbar.core.port.EconomyPort;

import java.util.Objects;
import java.util.UUID;

public final class VaultEconomyAdapter implements EconomyPort {
    private final VaultGateway vaultGateway;
    private final double fantuanPrice;
    private final double moneyUnitPrice;

    public VaultEconomyAdapter(VaultGateway vaultGateway, double fantuanPrice, double moneyUnitPrice) {
        this.vaultGateway = Objects.requireNonNull(vaultGateway, "vaultGateway");
        this.fantuanPrice = fantuanPrice;
        this.moneyUnitPrice = moneyUnitPrice;
    }

    @Override
    public boolean charge(UUID playerId, TableMode mode, int amount) {
        if (!mode.isWagerMode()) {
            return true;
        }
        return vaultGateway.withdraw(playerId, unitPrice(mode) * amount);
    }

    @Override
    public void reward(UUID playerId, TableMode mode, int amount) {
        if (!mode.isWagerMode()) {
            return;
        }
        vaultGateway.deposit(playerId, unitPrice(mode) * amount);
    }

    private double unitPrice(TableMode mode) {
        return mode == TableMode.FANTUAN_COIN ? fantuanPrice : moneyUnitPrice;
    }
}
