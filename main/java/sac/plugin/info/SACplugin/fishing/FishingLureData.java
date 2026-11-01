package sac.plugin.info.SACplugin.fishing;

public class FishingLureData {
    public String id;
    public String name;
    public String material;
    public int customModelData;
    public String rank;
    public String category;
    public String description;
    public long price;
    public int requiredProficiency = 1;

    // ==========================================
    // ランクを1段階上げる判定1回あたりの成功率に上乗せする%(基礎1%に加算)。
    // 例: 5なら、1回の判定が1%→6%になる。
    // ==========================================
    public double rareRankBonusPercent = 0.0;

    public FishingLureData() {}
}
