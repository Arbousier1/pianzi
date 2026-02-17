package cn.pianzi.liarbar.core.port;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public interface RandomSource {
    int nextIntInclusive(int minInclusive, int maxInclusive);

    default <T> void shuffle(List<T> list) {
        Collections.shuffle(list, ThreadLocalRandom.current());
    }

    static RandomSource threadLocal() {
        return (minInclusive, maxInclusive) -> {
            if (maxInclusive < minInclusive) {
                throw new IllegalArgumentException("maxInclusive must be >= minInclusive");
            }
            return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
        };
    }
}


