package sac.plugin.info.SACplugin.Smithing;

import java.util.Map;

public class ForgeRecipe {
    public String recipeId;
    public String resultWeaponId;
    public int requiredProficiency;
    public int cost;

    // ★新仕様: メイン素材1種 + サブ素材複数種に分離
    // mainMaterialId: メイン素材のアイテムID
    // mainMaterialAmount: メイン素材の必要数
    // subMaterials: サブ素材マップ（アイテムID → 必要数）
    public String mainMaterialId;
    public int mainMaterialAmount;
    public Map<String, Integer> subMaterials; // サブ素材（複数可、空でもOK）

    // ★後方互換: 旧形式のmaterialsフィールド（古いjsonが読めるよう残す）
    // ForgeManagerで読み込み後に新形式へ変換する
    public Map<String, Integer> materials;

    // ==========================================
    // ★追加: この武器を生産(クラフト)できるかどうか。
    // falseの場合、生産リストには表示されず、強化(既に持っている個体の強化)のみ可能になる。
    // 未指定(旧形式のjson)の場合はデフォルトtrue扱いになる。
    // ==========================================
    public boolean craftable = true;

    // ==========================================
    // ★追加: 生産ミニゲームで「普通成功」時に叩く基準回数。
    // 大成功は+1〜3回、奇跡的成功は+4〜6回して算出する。
    // 未指定(旧形式のjson)の場合は8回がデフォルト。
    // ==========================================
    public int baseHitCount = 8;
}
