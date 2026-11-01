package sac.plugin.info.SACplugin.accessory;

/**
 * accessory.jsonの"materials"内、1つのボスドロップ素材に対応するデータ。
 */
public class AccessoryMaterialData {
    public String id;               // 素材ID(ボスドロップのアイテムID。items.json等の既存IDと一致させる)
    public String name;             // 表示名(鑑定前の素材名として使う)
    public int floor;               // この素材が入手できる階層(ステータス抽選の強さに影響)
    public int requiredProficiency; // 加工スキルでアクセサリー化するために必要な熟練度
    // ==========================================
    // ★追加: 加工後のアクセサリーを装備するために必要なプレイヤーレベル。
    // 未指定(0)の場合は1(制限なし)として扱う。
    // ==========================================
    public int requiredLevel = 1;
}