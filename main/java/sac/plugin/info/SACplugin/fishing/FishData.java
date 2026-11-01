package sac.plugin.info.SACplugin.fishing;

public class FishData {
    public String id;
    public String name;
    public String material;
    public int customModelData;
    public String rank;
    public String category;
    public long price;            // 買取屋で売れる値段(基準値。実際の売値はサイズにより変動する)
    public String description;
    // ==========================================
    // ★追加: サイズの振れ幅(cm)。釣り上げるたびにこの範囲内でランダムに決まる。
    // sizeMax付近の大きな個体ほど、売却時に価格ボーナスがつく。
    // ==========================================
    public double sizeMin = 10.0;
    public double sizeMax = 30.0;

    public FishData() {}
}
