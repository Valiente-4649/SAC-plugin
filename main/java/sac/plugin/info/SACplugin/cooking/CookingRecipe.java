package sac.plugin.info.SACplugin.cooking;

import sac.plugin.info.SACplugin.item.SAOGeneralItemData;

import java.util.Map;

public class CookingRecipe {
    public String recipeId;
    public String method;              // "焼く", "煮込む", "茹でる", "揚げる"
    // ==========================================
    // ★変更: 食材IDだけを保持していたのをやめ、食材ごとの完全なアイテムデータ+必要個数を
    // レシピの中に直接持たせる形に変更(見やすさ優先)。
    // ただし魚(fishes.json)は既存の魚図鑑等の仕組みをそのまま使うため、
    // 見た目・価格等は埋め込まず、amount(個数)だけを持たせる(他のフィールドはnullのままでよい)。
    // ==========================================
    public Map<String, MaterialEntry> materials;
    public int targetTimeSeconds;      // 適切な調理時間(秒)
    public int baseToleranceTicks;     // 成功となる猶予時間(+-Tick)
    public String difficulty;          // 難易度表記 (S, A, B, C, D)
    // ★変更: 完成品・失敗品もIDだけでなく、フルのアイテムデータをレシピ内に直接持たせる
    public SAOGeneralItemData successItem;
    public SAOGeneralItemData failureEarlyItem;  // 早すぎた(生焼け)時にできるアイテム
    public SAOGeneralItemData failureLateItem;   // 遅すぎた(焦げ)時にできるアイテム
    public int requiredProficiency;    // 必要熟練度
    public int exp;                    // 成功時にもらえる料理経験値

    /**
     * 食材1つ分のデータ。SAOGeneralItemDataの全フィールド(name/material/price等)に
     * 加えて、このレシピで必要な個数(amount)を持つ。
     * 魚食材の場合はamountだけ指定し、他のフィールドは空(null)のままで良い
     * (fishes.json側の定義がそのまま使われる)。
     */
    public static class MaterialEntry extends SAOGeneralItemData {
        public int amount = 1;
    }
}
