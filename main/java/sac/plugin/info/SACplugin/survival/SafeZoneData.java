package sac.plugin.info.SACplugin.survival;
/**
 * ★新規: OPが自由に設置できる「セーフゾーン」1件分のデータ。
 * この範囲内にいるプレイヤーはSurvivalManagerによって毎秒HP自動回復する
 * (街の転移門周辺と同じ効果)。
 */
public class SafeZoneData {
    public String id;
    public String name;
    public String world;
    public double x, y, z;
    public double radius = 20.0;
}
