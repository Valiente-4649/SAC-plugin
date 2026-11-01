package sac.plugin.info.SACplugin.item;

public class SAOGeneralItemData {
    public String id;
    public String name;
    public String material;    // ★追加: "APPLE" や "POTION" など
    public long price;
    public long sellPrice;
    public int customModelData;
    public String rank;
    public String category; // 回復、食料、素材、その他
    public double value1;   // 回復量など
    public double value2;   // 使用時間など
    public String description;
    // ==========================================
    // ★追加: category="回復"のアイテム(ポーション等)の回復方式。
    // "instant" = 即時全回復(回復結晶で使用), "gradual" = value2秒かけてvalue1の固定量をゆっくり回復(ポーション)。
    // category="食料"のアイテムでは使用しない(value1をそのまま満腹ゲージ回復%として扱う)。
    // ==========================================
    public String healType;
}
