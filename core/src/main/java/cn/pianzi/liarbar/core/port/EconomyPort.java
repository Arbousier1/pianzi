package cn.pianzi.liarbar.core.port;

import cn.pianzi.liarbar.core.domain.TableMode;

import java.util.UUID;

public interface EconomyPort {
    boolean charge(UUID playerId, TableMode mode, int amount);

    void reward(UUID playerId, TableMode mode, int amount);

    static EconomyPort noop() {
        return new EconomyPort() {
            @Override
            public boolean charge(UUID playerId, TableMode mode, int amount) {
                return true;
            }

            @Override
            public void reward(UUID playerId, TableMode mode, int amount) {
            }
        };
    }
}


