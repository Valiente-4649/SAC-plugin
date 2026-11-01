package sac.plugin.info.SACplugin.mob;

import java.util.List;
import java.util.Map;

public class SAOMobData {
    public String id;
    public String name;
    public String category = "NORMAL"; //NORMAL, FIELD_BOSS, FLOOR_BOSS, MINION
    public int customModelData;
    public int level;
    public double maxHealth;
    public double attackDamage;
    public double movementSpeed; // バニラは0.23くらいが標準
    public double followRange;
    public String specialMotion; // 突進, 周囲, 遠隔, 前方 など
    public int exp;
    public long col;
    // ==========================================
    // ★追加: exp/colを範囲でランダム抽選できるようにする上限値。
    // 0(未設定)の場合は従来通りexp/colを固定値として使う。
    // 設定されている場合、[exp, expMax] / [col, colMax] の範囲でそれぞれ独立に抽選する。
    // ==========================================
    public int expMax = 0;
    public long colMax = 0;

    /** ★追加: expを[exp, expMax]の範囲で抽選する(expMax未設定なら固定のexpをそのまま返す) */
    public int rollExp() {
        if (expMax <= exp) return exp;
        return exp + (int) (Math.random() * (expMax - exp + 1));
    }

    /** ★追加: colを[col, colMax]の範囲で抽選する(colMax未設定なら固定のcolをそのまま返す) */
    public long rollCol() {
        if (colMax <= col) return col;
        return col + (long) (Math.random() * (colMax - col + 1));
    }

    // ==========================================
    // ★変更: dropsをMap(1アイテム1確率)から配列に変更。
    // 同じアイテムIDを複数回登録することで、「1個目は100%、2個目は50%、3個目は10%」
    // のように個数ごとに独立した確率でドロップ数を抽選できるようにする。
    // 例:
    // "drops": [
    //   {"id": "item1", "chance": 1.0},
    //   {"id": "item1", "chance": 0.5},
    //   {"id": "item1", "chance": 0.1},
    //   {"id": "item2", "chance": 0.05}
    // ]
    // ==========================================
    public List<DropEntry> drops;

    public static class DropEntry {
        public String id;
        public double chance;
    }
    // ==========================================
    // ★追加: 低確率レアモブ (「モブID」: 出現確率0.0〜1.0)
    // このモブが複数体スポーンする場合でも、対象になるのは常に1体のみ。
    // その1体だけが、ここに登録された確率でレアモブに差し替わってスポーンする。
    // ==========================================
    public Map<String, Double> raremob;
    public List<MobSkillData> specialAttacks;

    public SAOMobData(){}
}