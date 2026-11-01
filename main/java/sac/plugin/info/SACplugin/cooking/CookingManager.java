package sac.plugin.info.SACplugin.cooking;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.util.Reloadable;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CookingManager implements Reloadable {
    private final SACplugin plugin;
    private final List<CookingRecipe> recipes = new ArrayList<>();
    // ==========================================
    // ★変更: cooking_recipes.json自体には「items」という別セクションはもう無く、
    // 各レシピの中に直接、食材・完成品・失敗品のフルデータが埋め込まれている。
    // 読み込み時にレシピ群を1回スキャンして、この中身をまとめたものがcookingItems。
    // (ショップ・買取・モンスタードロップ等がSAOItemManager経由でIDだけから
    // アイテムデータを引けるようにするため)
    // ==========================================
    private final Map<String, sac.plugin.info.SACplugin.item.SAOGeneralItemData> cookingItems = new java.util.HashMap<>();

    public CookingManager(SACplugin plugin) {
        this.plugin = plugin;
        loadRecipes();
    }

    public void loadRecipes() {
        File file = new File(plugin.getDataFolder(), "cooking_recipes.json");
        if (!file.exists()) plugin.saveResource("cooking_recipes.json", false);

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<List<CookingRecipe>>(){}.getType();
            List<CookingRecipe> loaded = new Gson().fromJson(reader, type);
            if (loaded != null) {
                recipes.clear();
                recipes.addAll(loaded);
                rebuildCookingItemsIndex();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    /** ★追加: 各レシピに埋め込まれた食材・完成品・失敗品のデータを1つのマップにまとめ直す */
    private void rebuildCookingItemsIndex() {
        cookingItems.clear();
        for (CookingRecipe recipe : recipes) {
            if (recipe.materials != null) {
                for (Map.Entry<String, CookingRecipe.MaterialEntry> entry : recipe.materials.entrySet()) {
                    // ★魚食材(name等が埋め込まれていないもの)はここでは登録しない。
                    // fishes.json側がそのまま正のデータになる。
                    if (entry.getValue() != null && entry.getValue().name != null) {
                        cookingItems.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (recipe.successItem != null && recipe.successItem.id != null) {
                cookingItems.put(recipe.successItem.id, recipe.successItem);
            }
            if (recipe.failureEarlyItem != null && recipe.failureEarlyItem.id != null) {
                cookingItems.put(recipe.failureEarlyItem.id, recipe.failureEarlyItem);
            }
            if (recipe.failureLateItem != null && recipe.failureLateItem.id != null) {
                cookingItems.put(recipe.failureLateItem.id, recipe.failureLateItem);
            }
        }
    }

    public List<CookingRecipe> getRecipes() { return recipes; }

    /** ★追加: レシピに埋め込まれた食材・料理アイテムを取得する(SAOItemManagerが参照する) */
    public sac.plugin.info.SACplugin.item.SAOGeneralItemData getCookingItem(String id) {
        return cookingItems.get(id);
    }

    @Override
    public void reload() {
        loadRecipes();
    }

    public CookingRecipe getRecipeByIngredients(Map<String, Integer> inputMaterials, String method) {
        if (inputMaterials.isEmpty()) return null;

        for (CookingRecipe recipe : recipes) {
            if (!recipe.method.equals(method)) continue;

            // ① レシピにない余計な食材が混ざっていないかチェック
            boolean hasExtra = false;
            for (String inputId : inputMaterials.keySet()) {
                if (!recipe.materials.containsKey(inputId)) {
                    hasExtra = true;
                    break;
                }
            }
            if (hasExtra) continue; // 余計なものが混ざっていたら違うレシピ

            // ② レシピに必要な食材の「種類」がすべて揃っているかチェック（個数はボタン側で判定）
            boolean hasAllTypes = true;
            for (String reqMat : recipe.materials.keySet()) {
                if (!inputMaterials.containsKey(reqMat)) {
                    hasAllTypes = false;
                    break;
                }
            }

            // 種類が完全に一致していればそのレシピを返す
            if (hasAllTypes && !hasExtra) {
                return recipe;
            }
        }
        return null; // 一致するものがなければnull
    }

    // 調理台の設置
    public void spawnCookingStation(Location location) {
        Location loc = location.clone();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setY(loc.getBlockY());
        loc.setZ(loc.getBlockZ() + 0.5);
        // ★追加: 元のlocationが持つyaw/pitchが意図せず影響している可能性があるため、明示的に0にリセットする
        loc.setYaw(0f);
        loc.setPitch(0f);

        // 燻製器の見た目
        BlockDisplay display = (BlockDisplay) loc.getWorld().spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        // ★追加: スポーン直後の補間によるズレ・傾きを防ぐ(Multiverse等の別ワールドで顕著)
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.setBlock(Bukkit.createBlockData(org.bukkit.Material.SMOKER));
        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0, -0.5f), new org.joml.Quaternionf(),
                new Vector3f(1, 1, 1), new org.joml.Quaternionf()
        ));
        display.addScoreboardTag("sao_cooking_display");

        Location textLoc = loc.clone().add(0, 1.2, 0);
        TextDisplay textDisplay = (TextDisplay) loc.getWorld().spawnEntity(textLoc, EntityType.TEXT_DISPLAY);
        textDisplay.setText("§6調理台");
        textDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        textDisplay.addScoreboardTag("sao_cooking_display");

        Interaction interaction = (Interaction) loc.getWorld().spawnEntity(loc, EntityType.INTERACTION);
        interaction.setInteractionHeight(1.0f);
        interaction.setInteractionWidth(1.0f);
        interaction.setResponsive(true);
        interaction.addScoreboardTag("sao_cooking_station");
    }

    public void removeCookingStation(Location location) {
        for (org.bukkit.entity.Entity entity : location.getWorld().getNearbyEntities(location, 3, 3, 3)) {
            if (entity.getScoreboardTags().contains("sao_cooking_display") ||
                    entity.getScoreboardTags().contains("sao_cooking_station")) {
                entity.remove();
                entity.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, entity.getLocation().add(0, 0.5, 0), 5, 0.2, 0.2, 0.2, 0.05);
            }
        }
    }
}