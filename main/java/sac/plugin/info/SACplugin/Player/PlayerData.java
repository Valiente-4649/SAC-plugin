package sac.plugin.info.SACplugin.Player;

import java.util.HashMap;
import java.util.Map;

public class PlayerData {
    public int level = 1;
    // 活力(旧称:睡眠ステータス)。既存セーブに項目がない場合は100で初期化。
    public double vitality = 100.0;
    public boolean restingOffline = false;
    public long vitalityLogoutAt = 0L;
    public double currentHP = 250.0;
    public double maxHP = 250.0;
    public int str = 0;
    public int agi = 0;
    public long col = 1200;
    // ★追加: スキルポイントリセットの最終実行時刻（エポックミリ秒）。0=未実行
    public long lastSkillResetTime = 0L;
    public int exp = 0; // 経験値
    public int skillPoint = 0; //未使用のスキルポイント
    public String selectedSkillId = "";
    public boolean isDead = false;
    public int deathTimer = 0; // ★追加: 死亡猶予タイマー（秒）
    public String currentQuestId = ""; // 現在受けているクエストID (空文字は未受注)
    public int questProgress = 0;      // 討伐数や納品数のカウント
    public boolean questCompleted = false; // 達成して報告待ちかどうか
    public int defense = 0;     // 合計防御力
    public int armorWeight = 0; // 防具の合計重量
    // ==========================================
    // ★追加: 装備中の防具から得られるSTR/AGIボーナス。
    // data.str/data.agi(スキルポイントで振った素の値)とは別枠で保持し、
    // 実際の速度計算・攻撃速度計算などでは (data.str + armorStrBonus) のように
    // 使用箇所側で合算して使う。こうすることでsyncHealth()を何度呼んでも
    // 二重加算にならない。
    // ==========================================
    public int armorStrBonus = 0;
    public int armorAgiBonus = 0;
    // ==========================================
    // ★追加: 装備中のアクセサリーから得られる命中率・REQボーナス。
    // STR/AGI/HPのボーナスはarmorStrBonus/armorAgiBonus/maxHP計算に直接合算するため
    // 専用フィールドは不要だが、ACC(命中率)とREQ(装備必要レベル緩和)は他の値と
    // 意味が異なるため専用に保持する。
    // ==========================================
    public int accAccuracyBonus = 0;
    public int accReqBonus = 0;

    public Map<String, Integer> proficiency = new HashMap<>();
    public Map<String, Integer> usedDurability = new HashMap<>();
    public Map<String, Integer> proficiencyExp = new HashMap<>();
    public java.util.Map<String, Integer> questCounts = new java.util.HashMap<>();
    // ★追加: 装備アイテムを保存するマップ
    public Map<String, org.bukkit.inventory.ItemStack> equipment = new HashMap<>();
    // ==========================================
    // ★追加: プレイヤー専用のストレージ(ラージチェスト54枠)
    // ==========================================
    public org.bukkit.inventory.ItemStack[] storage = new org.bukkit.inventory.ItemStack[54];
    public java.util.List<String> completedTutorials = new java.util.ArrayList<>();
    public java.util.List<String> caughtFishes = new java.util.ArrayList<>();
    // ==========================================
    // ★追加: 自分が討伐したフロアボスのIDリスト (個別管理)
    // ==========================================
    public java.util.List<String> defeatedBosses = new java.util.ArrayList<>();
    // ==========================================
    // ★変更: 6固定スロット→レベル制の可変スロットに変更。
    // 配列自体は将来の最大値(12)分を確保しておき、実際に使えるスロット数は
    // レベルとlevel_limits.jsonのmax_skill_slotsから動的に計算する
    // (SkillMenuManager.getAvailableSlotCount参照)。
    // ==========================================
    public String[] equippedSkills = new String[]{"", "", "", "", "", "", "", "", "", "", "", ""};
    // ==========================================
    // ★追加: 6固定スロット→可変スロット制への移行が済んでいるかどうか。
    // true=移行済み(または移行対象外の新規プレイヤー)。
    // ==========================================
    public boolean skillSlotMigrated = false;
    // ==========================================
    // ★追加: クエスト報酬などで後から解放された武器種(体術など)のカテゴリ名リスト。
    // weapon_types.jsonでrequiresUnlock=trueの武器種は、ここに含まれていない限り
    // スキルカタログメニューに表示されない。
    // ==========================================
    public java.util.List<String> unlockedWeaponTypes = new java.util.ArrayList<>();
    public java.util.List<java.util.UUID> friends = new java.util.ArrayList<>();
    // ==========================================
    // ★変更: デイリークエスト関連
    // 「階層ごと」ではなく「1日1回・全階層共通」の制限にしたため、単一の日付で管理する
    // 最後にデイリーをクリアした論理日付("yyyy-MM-dd", 4時リセット基準)。未クリアなら空文字。
    // ==========================================
    public String lastDailyClearDate = "";
    // ==========================================
    // ★追加: 投票報酬(ドロップ率アップバフ)を最後に受け取った論理日付("yyyy-MM-dd", 4時リセット基準)。
    // 未受取なら空文字。ログイン時にこの日付が今日と違う場合のみ投票案内を表示する。
    // ==========================================
    public String lastVoteDate = "";
    // ==========================================
    // ★追加: デスペナルティ用。死亡回数の累計と、直近のログイン時刻(エポックミリ秒)。
    // lastLoginTimeは「黒鉄宮」の死亡回数ランキング表示で、過去1週間以内にログインした
    // プレイヤーだけを対象にするために使う。
    // ==========================================
    public int deathCount = 0;
    public long lastLoginTime = 0L;
    // ==========================================
    // ★追加: レベル必要経験値カーブ変更に伴う、一度きりの再計算移行が
    // 済んでいるかどうか。true=移行済み(または移行対象外の新規プレイヤー)。
    // ==========================================
    public boolean expCurveMigrated = false;
    // ==========================================
    // ★追加: 「レベル10ごとに必要経験値+500」仕様変更に伴う、一度きりの再計算移行フラグ。
    // 前回のexpCurveMigratedとは別名にする(前回の移行を既に終えたプレイヤーも
    // 今回の移行対象に含める必要があるため、同じフラグを使い回さない)。
    // ==========================================
    public boolean expCurveMigratedV2 = false;
    // ==========================================
    // ★追加: unlockedWeaponTypesが保存されていなかった不具合の影響で、
    // 体術などクエスト解放済みのはずのスキルが消えてしまった人向けの
    // 一度きりの復元チェックが済んでいるかどうか。
    // ==========================================
    public boolean questSkillUnlockChecked = false;
    // ==========================================
    // ★追加: バニラの空腹システムを置き換える独自の満腹ゲージ(0-100%)。
    // 100=満腹。SurvivalManagerが5秒おきに減少判定を行う。
    // ==========================================
    public double hunger = 100.0;
    // ==========================================
    // ★追加: 「腹持ち%」の隠しバッファ(0-100%、目に見えない)。
    // 食料のvalue2から付与される。0より大きい間は満腹度(hunger)の減少をせき止め、
    // 代わりにこのバッファ自体が減少する。バッファが残っている間に別の食料を食べても
    // (腹持ち付与は)上書き・加算されない。0になって初めて次の食料の腹持ちが乗る。
    // ==========================================
    public double satietyBuffer = 0.0;
    public PlayerData() {
        // ここに書けば put が赤くなりません
        proficiency.put("片手剣", 1);
        proficiency.put("細剣", 1); // 細剣(Rapier)

        // 耐久値カウントの初期値も 0 でセットしておくと安心です
        usedDurability.put("片手剣", 0);
        usedDurability.put("細剣", 0);
    }
}