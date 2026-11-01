package sac.plugin.info.SACplugin.Tutorial;


import java.util.List;
import java.util.Map;

/**
 * tutorial.jsonの1ステップ分のデータ。
 * - message/messageLocation: 表示するメッセージの内容と座標(テキストディスプレイ)
 * - teleport: このステップ表示時にプレイヤーを転移させる座標(未記入なら転移しない)
 * - nextButtonLocation: 「次へ」テキストディスプレイの座標
 * - damage: true=ダメージを受ける(無敵解除) / false=ダメージを受けない(無敵) / null(未記入)=現状維持
 * - items: 付与するアイテム(武器・防具・一般アイテムのID→個数)
 * - endTutorial: このステップの「次へ」を押した時にチュートリアルを終了するか
 */
public class TutorialStepData {
    public List<String> message;
    public TutorialLocation messageLocation;
    public TutorialLocation teleport;
    public TutorialLocation nextButtonLocation;
    public Boolean damage;
    public Map<String, Integer> items;
    // ==========================================
    // ★追加: このステップ表示時に、指定した座標へモブを1体だけスポーンさせる。
    // mobId/mobLocationのどちらか、または両方が未記入ならスポーンしない。
    // ==========================================
    public String mobId;
    public TutorialLocation mobLocation;
    public boolean endTutorial = false;
}
