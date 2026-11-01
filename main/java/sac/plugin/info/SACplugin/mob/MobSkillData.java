package sac.plugin.info.SACplugin.mob;

public class MobSkillData {
    public String attackId;         // charge, circle, cone_front など
    public int rangeLevel;          // 範囲の広さ (1, 2, 3...)
    public double damageMultiplier; // ダメージ倍率
    public int chargeTicks;         // 発動までの溜め時間 (20Ticks = 1秒)

    // ==========================================
    // ★変更: この特殊攻撃が命中した時の状態異常付与。
    // スタン(旧・麻痺システム): stunChance(0.0〜1.0)の確率で、stunLevel(1〜5)秒間、
    //   移動も武器攻撃・ソードスキルもできなくなる。解毒ポーションでは解除できない。
    // 麻痺(新): paralyzeChance(0.0〜1.0)の確率で、paralyzeLevel(1〜5、=15/20/25/30/35秒)の間、
    //   スタンと同じ状態になるが、解毒ポーションで解除できる。
    // 毒: poisonChance(0.0〜1.0)の確率で、poisonDuration秒間、1秒ごとに最大HPの3%を失う。
    // 全て未設定(0)ならその状態異常は一切付与されない。
    // ==========================================
    public double stunChance = 0.0;
    public int stunLevel = 0;
    public double paralyzeChance = 0.0;
    public int paralyzeLevel = 0;
    public double poisonChance = 0.0;
    public double poisonDuration = 0.0;
}
