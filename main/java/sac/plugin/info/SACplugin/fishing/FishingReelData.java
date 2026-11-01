package sac.plugin.info.SACplugin.fishing;

public class FishingReelData {
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
    // 魚が暴れている間の「距離が離れる勢い」を何%抑えるか(0〜100)。
    // 例: 50なら、暴れ中に本来1.0離れるところを0.5に抑える。
    // ==========================================
    public double rageSuppressPercent = 0.0;

    public FishingReelData() {}
}
