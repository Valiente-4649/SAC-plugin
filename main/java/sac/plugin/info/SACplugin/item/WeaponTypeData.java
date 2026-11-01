package sac.plugin.info.SACplugin.item;

/**
 * weapon_types.json の1エントリに対応するデータクラス。
 * 武器種ごとの通常攻撃リーチ・スロット番号・説明文を保持する。
 */
public class WeaponTypeData {
    public String category;     // 武器種名 (SAOWeapon.categoryと一致させる)
    public double normalReach;  // 通常攻撃のリーチ (ブロック数)
    public int slotIndex;       // スキルメニューでのスロット番号
    public String description;  // メニューでの武器種説明

    // --- 攻撃速度関連 ---
    // 最速値。4/4拍子のBPMで指定する（例: 140 = 1分間に140拍のペースが最速）。
    // 0以下（未設定）の場合はWeaponTypeManager側でデフォルト値を使用する。
    public double maxSpeedBpm;

    // 重量(weight)が振り速度の計算に影響するかどうか。
    // 短剣のように重量を無視して速度を決めたい武器種は false にする。
    public boolean weightAffectsSpeed = true;

    // STRが振り速度の計算に影響するかどうか。
    // false の場合、重量・STRに関わらず常にmaxSpeedBpm(最速値)固定で振れる。
    public boolean strAffectsSpeed = true;

    // --- 攻防関連 ---
    // 攻撃がノックバックするかどうか。falseにすると被弾者を一切怯ませない(細剣・短剣・刀のような連撃武器向け)。
    // 未設定の場合はtrue(通常のノックバックあり)。
    public boolean knockback = true;

    // 右クリックでパリィ(受け流し)が可能かどうか。
    // 未設定の場合はfalse(パリィ不可)。
    public boolean canParry = false;

    // --- メニュー表示関連 ---
    // スキルカタログメニューでのアイコンのCustomModelData。0(未設定)の場合はCMD無しの素のIRON_SWORDになる。
    public int iconCustomModelData = 0;

    // --- 素手武器種(体術など)関連 ---
    // trueの場合、この武器種は武器アイテムを一切持たず、素手のまま運用する(SAOWeapon不要)。
    public boolean unarmed = false;

    // 熟練度Lv.1時点の素手攻撃力
    public double unarmedBaseDamage = 0;

    // 熟練度1につき加算される素手攻撃力
    public double unarmedDamagePerLevel = 0;

    // この武器種がスキルスロットにセットされている間、移動速度に追加で加算される割合(%)。
    // AGIによる速度加算・防具重量による減算とは別枠で乗算される。
    public double movementSpeedBonusPercent = 0;

    // --- 解放制限関連 ---
    // trueの場合、この武器種はスキルカタログメニューに常時表示されない。
    // プレイヤーのPlayerData.unlockedWeaponTypesに含まれている場合のみ表示・選択可能になる。
    // (クエスト報酬などで後から解放する武器種向け)
    public boolean requiresUnlock = false;
}
