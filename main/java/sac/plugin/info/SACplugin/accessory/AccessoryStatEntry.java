package sac.plugin.info.SACplugin.accessory;

/**
 * アクセサリー(または鑑定素材)に付与された、抽選済みのステータス1つ分。
 * type: "HP_PERCENT" / "HP_FLAT" / "STR_PERCENT" / "STR_FLAT" /
 *       "AGI_PERCENT" / "AGI_FLAT" / "ACC" / "REQ"
 */
public class AccessoryStatEntry {
    public String type;
    public double value;

    public AccessoryStatEntry() {}

    public AccessoryStatEntry(String type, double value) {
        this.type = type;
        this.value = value;
    }
}
