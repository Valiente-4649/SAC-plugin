package sac.plugin.info.SACplugin.achievement;

import sac.plugin.info.SACplugin.Player.PlayerData;

/**
 * 実績1件の定義。定義は毎回データ(JSON・宝箱・クエスト等)から組み立てられる「使い捨て」のもので、
 * 達成条件が変わると signature(条件の署名)が変わる。署名が変わった実績は自動で取り消され、
 * 進捗(AchData のカウンタ)は引き継がれたまま、新しい条件で達成していれば自動で再付与される。
 */
public class AchievementDef {

    /** 進捗の評価: {現在値, 目標値} を返す。現在値>=目標値で達成。 */
    public interface Evaluator {
        int[] eval(PlayerData stats, AchData data);
    }

    /** ユニーク実績の先着判定用: 同じ判定タイミングに複数人が条件を満たした時の優先度(小さい方が優先)。 */
    public interface Priority {
        long of(AchData data);
    }

    public final String id;
    public final AchievementType type;
    public final String name;
    /** 獲得方法の説明(ノーマルは常に表示。エクストラ/ユニークは獲得後のみ表示) */
    public final String description;
    public final String signature;
    public final Evaluator evaluator;
    /** ユニークのみ */
    public final Priority priority;

    public AchievementDef(String id, AchievementType type, String name, String description,
                          String signature, Evaluator evaluator, Priority priority) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.description = description;
        this.signature = signature;
        this.evaluator = evaluator;
        this.priority = priority;
    }

    public boolean isUnique() { return type == AchievementType.UNIQUE; }
}
