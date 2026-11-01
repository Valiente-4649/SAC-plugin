package sac.plugin.info.SACplugin.treasure;

import java.util.ArrayList;
import java.util.List;

public class TreasureData {
    public String id;            // 座標文字列 "world:x:y:z"
    public String name;          // 宝箱名
    public long rewardCol;       // 報酬Col
    public int rewardExp;        // 報酬EXP
    public String world;
    public double x, y, z;
    public float yaw;            // チェストの向き（設置時のプレイヤーの向きを反映）

    // 開封済みプレイヤーのUUID文字列リスト（軽量に保存するためStringのリストで管理）
    public List<String> openedBy = new ArrayList<>();

    public TreasureData() {}
}