package cn.pianzi.liarbar.core.runtime;

import cn.pianzi.liarbar.core.config.TableConfig;
import cn.pianzi.liarbar.core.port.EconomyPort;
import cn.pianzi.liarbar.core.port.RandomSource;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LiarBarRuntimeManager implements AutoCloseable {
    private final Map<String, AsyncTableRuntime> runtimes = new ConcurrentHashMap<>();

    public AsyncTableRuntime createTable(String tableId, TableConfig config, EconomyPort economy, RandomSource random) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(random, "random");

        return runtimes.computeIfAbsent(tableId, id -> {
            LiarBarTable table = new LiarBarTable(id, config, economy, random);
            return new AsyncTableRuntime(table);
        });
    }

    public Optional<AsyncTableRuntime> getTable(String tableId) {
        return Optional.ofNullable(runtimes.get(tableId));
    }

    public void removeTable(String tableId) {
        AsyncTableRuntime runtime = runtimes.remove(tableId);
        if (runtime != null) {
            runtime.close();
        }
    }

    @Override
    public void close() {
        for (AsyncTableRuntime runtime : runtimes.values()) {
            runtime.close();
        }
        runtimes.clear();
    }
}


