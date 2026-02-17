package cn.pianzi.liarbar.core.domain;

public record Card(long id, CardRank rank, boolean demon) {
    public Card asDemon() {
        if (demon) {
            return this;
        }
        return new Card(id, rank, true);
    }

    public boolean isMainLike(CardRank mainRank) {
        return demon || rank == CardRank.J || rank == mainRank;
    }
}


