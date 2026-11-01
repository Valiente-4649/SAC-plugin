package sac.plugin.info.SACplugin.Smithing;

import java.util.Map;

public class ArmorForgeRecipe {
    public String recipeId;
    public String resultArmorId;
    public int requiredProficiency;
    public int cost;

    // メイン素材1種 + サブ素材複数種
    public String mainMaterialId;
    public int mainMaterialAmount;
    public Map<String, Integer> subMaterials; // サブ素材（複数可、空でもOK）

    // ★追加: この防具の強化試行回数の上限(武器のWeaponUpgradeData.maxUpgradesに相当)
    public int maxUpgrades;

    // ==========================================
    // ★追加: 生産ミニゲームで「普通成功」時に叩く基準回数。
    // 大成功は+1〜3回、奇跡的成功は+4〜6回して算出する。
    // 未指定(旧形式のjson)の場合は8回がデフォルト。
    // ==========================================
    public int baseHitCount = 8;
}
