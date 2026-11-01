package sac.plugin.info.SACplugin.item;

import java.util.Map;

public class SAOArmor {
    public String id;               // 内部ID
    public String name;             // 表示名
    public String material;         // マイクラのアイテムの種類(Stringで読み込み)
    public int customModelData;     // テクスチャ用ID
    public String equipType;        // 装備部位 (HEAD, CHEST, ARM, LEGS, BOOTS)
    // ==========================================
    // ★追加: 防具の素材系統 ("革" / "布" / "金属")。
    // 金属防具鍛造でtype="金属"の防具のみ生産可能。
    // ==========================================
    public String type;
    public int defense;             // 防御力
    public int durability;          // 現在の耐久値
    public int maxDurability;       // 最大耐久値
    public int weight;              // 重さ
    public int requires;            // 必要筋力値(レベル)
    public int upgradeLimit;        // 強化上限
    public long price;              // ★【追加】ショップ用の値段
    public Map<String, double[]> equipBonuses; // 特殊効果 [最小値, 最大値]

    public SAOArmor() {}
}
