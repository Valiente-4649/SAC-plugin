package sac.plugin.info.SACplugin.achievement;

/** 実績の種別。 */
public enum AchievementType {
    /** 獲得方法をメニューで確認できる実績 */
    NORMAL("ノーマル", "§a"),
    /** 獲得するまでメニューに存在自体が表示されない実績 */
    EXTRA("エクストラ", "§b"),
    /** 各基準で現在の最高順位(最初の達成者)1人だけが持つ実績。獲得するまで存在自体が表示されない */
    UNIQUE("ユニーク", "§6");

    public final String label;
    public final String color;

    AchievementType(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
