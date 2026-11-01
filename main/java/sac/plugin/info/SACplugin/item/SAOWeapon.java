package sac.plugin.info.SACplugin.item;

import org.bukkit.Material;

public class SAOWeapon {
    public String id;               // 内部ID (elucidatorなど)
    public String name;             // 表示名
    public String category;
    public Material material;       // マイクラのアイテムの種類
    public int customModelData;     // テクスチャ用ID
    public String range;            // 射程 (Shortなど)
    public String type;             // 属性 (Slashなど)
    public double minAttack;        // 最小攻撃力
    public double maxAttack;        // 最大攻撃力
    public int durability;          // 現在の耐久値
    public int maxDurability;       // 最大耐久値
    public double weight;           // 重さ
    public int requires;            // 必要筋力値
    public long price;    // JSONの "price" に対応

    public SAOWeapon() {}

    // 武器データを作るためのコンストラクタ
    public SAOWeapon(String id, String name, Material material, int customModelData, String range, String type,
                     double minAttack, double maxAttack, int durability, int maxDurability, double weight, int requires) {
        this.id = id;
        this.name = name;
        this.material = material;
        this.customModelData = customModelData;
        this.range = range;
        this.type = type;
        this.minAttack = minAttack;
        this.maxAttack = maxAttack;
        this.durability = durability;
        this.maxDurability = maxDurability;
        this.weight = weight;
        this.requires = requires;
    }
}
