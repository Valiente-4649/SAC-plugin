package sac.plugin.info.SACplugin.fishing;

public class FishingRodData {
    public String id;
    public String name;
    public String material;
    public int customModelData;
    public String rank;
    public String category;
    public String description;
    public long price;

    // ==========================================
    // ★変更: 竿の役割は「待ち時間(ヒットまでの早さ)」に一本化。
    // 暴れる強さを抑える力はリール、高ランクの魚が釣れやすくなる確率はルアーが担当する。
    // ==========================================
    public double waitMultiplier; // 待ち時間の倍率 (低いほど早く釣れる)
    public int requiredProficiency = 1;

    // ==========================================
    // ★追加: 竿本体の耐久値。修理は無く、0になったら壊れて使えなくなる(買い直し)。
    // 釣りを試みる(セット開始)たびに、成功・失敗を問わず1減る。
    // ==========================================
    public int maxDurability = 30;

    public FishingRodData() {}
}
