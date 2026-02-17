package cn.pianzi.liarbar.core.domain;

public enum TableMode {
    LIFE_ONLY,
    FANTUAN_COIN,
    KUNKUN_COIN;

    public boolean isWagerMode() {
        return this != LIFE_ONLY;
    }
}


