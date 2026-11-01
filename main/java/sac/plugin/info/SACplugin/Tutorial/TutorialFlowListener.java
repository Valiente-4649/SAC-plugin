package sac.plugin.info.SACplugin.Tutorial;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ★新規: tutorial.json駆動のチュートリアル進行エンジン。
 * 各ステップは「メッセージのテキストディスプレイ」+「次へのテキストディスプレイ」で構成され、
 * 「次へ」を右クリックする度に次のステップへ進む(押す→1度消す→2秒後に次の座標へ設置)。
 *
 * 進行状況はTutorialManager.activeTutorial(チュートリアルID)/tutorialProgress(現在のステップIndex、0始まり)
 * をそのまま流用する(既存のNPC側のチェックと衝突しないようにするため)。
 */
public class TutorialFlowListener implements Listener {
    private final SACplugin plugin;

    private static final String NEXT_TAG = "sao_tutorial_next";
    private static final String CHOICE_YES_TAG = "sao_tutorial_choice_yes";
    private static final String CHOICE_NO_TAG = "sao_tutorial_choice_no";

    // プレイヤーごとに現在表示中のディスプレイ・当たり判定エンティティを保持(掃除用)
    private static final java.util.Map<UUID, List<Entity>> activeDisplays = new java.util.HashMap<>();
    private static final java.util.Set<UUID> pendingTransitions = new java.util.HashSet<>();
    private static final java.util.Set<UUID> boarCombatPlayers = new java.util.HashSet<>();

    // ==========================================
    // ★追加: 複数人が同時にチュートリアルを受けた時、テキストディスプレイやモブの
    // 座標が完全に重ならないよう、プレイヤーごとに小さなオフセットを割り当てる。
    // ==========================================
    private static final java.util.Map<UUID, Integer> playerSlot = new java.util.HashMap<>();
    private static final java.util.Set<Integer> usedSlots = new java.util.TreeSet<>();
    // 各スロットのオフセット(X,Z)。0番は無補正(通常プレイ用)、それ以降は同時進行者用に少しずつずらす。
    private static final double[][] SLOT_OFFSETS = {
            {0, 0}, {-14, 0}, {-28, 0}, {-42, 0}, {-56, 0}, {-70, 0}, {-84, 0}, {-98, 0}, {-112, 0}
    };

    // ★追加: このプレイヤーの今の進行がアイテム付与対象かどうか(リンクスタート経由=true、NPCからの再受講=false)
    private static final java.util.Map<UUID, Boolean> grantItemsMap = new java.util.HashMap<>();

    private int assignSlot(UUID uuid) {
        if (playerSlot.containsKey(uuid)) return playerSlot.get(uuid);
        for (int i = 0; i < SLOT_OFFSETS.length; i++) {
            if (!usedSlots.contains(i)) {
                usedSlots.add(i);
                playerSlot.put(uuid, i);
                return i;
            }
        }
        // 全スロット使用中なら重ねずに空き待ちとする
        return -1;
    }

    private void releaseSlot(UUID uuid) {
        Integer slot = playerSlot.remove(uuid);
        if (slot != null) usedSlots.remove(slot);
    }

    /** 割り当てられたスロット分だけ座標をずらしたLocationを返す(null-safe) */
    private Location offsetLocation(Location base, UUID uuid) {
        if (base == null) return null;
        int slot = assignSlot(uuid);
        if (slot < 0) return null;
        double[] off = SLOT_OFFSETS[slot % SLOT_OFFSETS.length];
        return base.clone().add(off[0], 0, off[1]);
    }

    public TutorialFlowListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    /**
     * チュートリアルのボア戦で、当該プレイヤー専用ボアからの攻撃のみ保護する。
     * setInvulnerable(true)はAIのターゲット選択に影響するため使用しない。
     */
    public static boolean isProtectedFromTutorialBoar(Player player, Entity attacker) {
        if (player == null || attacker == null) return false;
        UUID uuid = player.getUniqueId();
        String tutorialId = TutorialManager.activeTutorial.get(uuid);
        if (tutorialId == null || !boarCombatPlayers.contains(uuid)) return false;
        // 他プレイヤーのボアや通常のフィールドボアへの無敵化はしない。
        return attacker.getScoreboardTags().contains("sao_tutorial_mob")
                && attacker.getScoreboardTags().contains("sao_mob_frenzy_boar")
                && attacker.getScoreboardTags().contains("sao_tut_owner_" + uuid);
    }

    // ==========================================
    // ★初回選択(受ける/受けない)ボタンの設置。特設ゾーンに常設する想定。
    // ==========================================
    public void spawnInitialChoiceButtons(TutorialLocation yesLoc, TutorialLocation noLoc) {
        if (yesLoc != null) spawnStaticChoiceButton(yesLoc, CHOICE_YES_TAG, "§a[ チュートリアルを受ける ]");
        if (noLoc != null) spawnStaticChoiceButton(noLoc, CHOICE_NO_TAG, "§c[ 受けない ]");
    }

    private void spawnStaticChoiceButton(TutorialLocation locData, String tag, String text) {
        Location loc = locData.toBukkitLocation();
        if (loc == null) return;
        // /tutorial spawnchoice の再実行でも選択ボタンが増殖しないようにする
        for (Entity old : loc.getWorld().getNearbyEntities(loc, 1.5, 2.0, 1.5)) {
            if (old.getScoreboardTags().contains(tag) &&
                    (old instanceof TextDisplay || old instanceof Interaction)) old.remove();
        }
        TextDisplay display = (TextDisplay) loc.getWorld().spawnEntity(loc, org.bukkit.entity.EntityType.TEXT_DISPLAY);
        display.setText(text);
        display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        display.addScoreboardTag(tag);

        Interaction interaction = (Interaction) loc.getWorld().spawnEntity(loc, org.bukkit.entity.EntityType.INTERACTION);
        interaction.setInteractionHeight(1.0f);
        interaction.setInteractionWidth(1.0f);
        interaction.setResponsive(true);
        interaction.addScoreboardTag(tag);
    }

    @EventHandler
    public void onChoiceClick(PlayerInteractAtEntityEvent event) {
        Entity clicked = event.getRightClicked();
        Player player = event.getPlayer();

        if (clicked.getScoreboardTags().contains(CHOICE_YES_TAG)) {
            event.setCancelled(true);
            startTutorial(player, "SACの基礎");
        } else if (clicked.getScoreboardTags().contains(CHOICE_NO_TAG)) {
            event.setCancelled(true);
            declineTutorial(player);
        } else if (clicked.getScoreboardTags().contains(NEXT_TAG)) {
            event.setCancelled(true);
            String ownerUuidStr = clicked.getScoreboardTags().stream()
                    .filter(t -> t.startsWith("sao_tut_owner_"))
                    .map(t -> t.replace("sao_tut_owner_", ""))
                    .findFirst().orElse(null);
            if (ownerUuidStr == null || !ownerUuidStr.equals(player.getUniqueId().toString())) {
                return; // 他人の「次へ」ボタンは反応しない
            }
            advanceStep(player);
        }
    }

    /** 「受けない」を選んだ場合: 既存仕様通り始まりの街へ転移し、Menu・転移結晶を付与する */
    private void declineTutorial(Player player) {
        clearPlayerDisplays(player.getUniqueId());
        player.teleport(TutorialManager.TOWN_SPAWN);
        player.getInventory().addItem(TutorialManager.getSkillMenuItem());
        ItemStack tpCrystal = TutorialManager.getTeleportCrystal(plugin);
        if (tpCrystal != null && tpCrystal.getType() != org.bukkit.Material.AIR) {
            player.getInventory().addItem(tpCrystal);
        }
        player.sendMessage("§a[System] 《Menu》と転移結晶を受け取りました。");
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    // ==========================================
    // ★チュートリアル開始・進行
    // ==========================================
    /** リンクスタート経由(アイテム付与あり)での開始 */
    public void startTutorial(Player player, String tutorialId) {
        startTutorial(player, tutorialId, true);
    }

    /**
     * @param grantItems trueならステップのitems/mobIdを実際に付与・スポーンする。
     *                   falseなら(NPCからの再受講など)アイテム付与・モブスポーンをスキップし、
     *                   メッセージ表示のみ行う。
     */
    public void startTutorial(Player player, String tutorialId, boolean grantItems) {
        List<TutorialStepData> steps = plugin.getTutorialJsonManager().getSteps(tutorialId);
        if (steps == null || steps.isEmpty()) {
            player.sendMessage("§c[System] このチュートリアルのデータが見つかりません(tutorial.json未設定: " + tutorialId + ")");
            return;
        }
        UUID uuid = player.getUniqueId();
        pendingTransitions.remove(uuid);
        boarCombatPlayers.remove(uuid);
        clearPlayerDisplays(uuid);
        if (assignSlot(uuid) < 0) {
            player.sendMessage("§c[System] チュートリアルが混雑しています。少し時間を空けてください。");
            return;
        }
        grantItemsMap.put(uuid, grantItems);
        TutorialManager.activeTutorial.put(player.getUniqueId(), tutorialId);
        TutorialManager.tutorialProgress.put(player.getUniqueId(), 0);
        showStep(player, tutorialId, 0);
    }

    private void advanceStep(Player player) {
        UUID uuid = player.getUniqueId();
        String tutorialId = TutorialManager.activeTutorial.get(uuid);
        if (tutorialId == null || !pendingTransitions.add(uuid)) return;

        List<TutorialStepData> steps = plugin.getTutorialJsonManager().getSteps(tutorialId);
        if (steps == null) { pendingTransitions.remove(uuid); return; }

        int currentIndex = TutorialManager.tutorialProgress.getOrDefault(uuid, 0);
        TutorialStepData currentStep = (currentIndex >= 0 && currentIndex < steps.size()) ? steps.get(currentIndex) : null;

        // 現在の表示を1度消す
        clearPlayerDisplays(uuid);
        // ボア戦を離れた瞬間に無敵を解除（次ステップにdamage未記入でも残さない）
        boarCombatPlayers.remove(uuid);
        if (currentStep != null && "frenzy_boar".equalsIgnoreCase(currentStep.mobId)) {
            player.setInvulnerable(false);
        }

        boolean shouldEnd = currentStep != null && currentStep.endTutorial;

        // ★仕様: 押す度に1度消し、2秒後に次を表示する
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTransitions.remove(uuid);
            if (!player.isOnline() || !tutorialId.equals(TutorialManager.activeTutorial.get(uuid))
                    || TutorialManager.tutorialProgress.getOrDefault(uuid, -1) != currentIndex) return;
            if (shouldEnd) {
                endTutorial(player, tutorialId);
                return;
            }
            int nextIndex = currentIndex + 1;
            if (nextIndex >= steps.size()) {
                // endTutorialが指定されていないままステップが尽きた場合も終了扱いにする
                endTutorial(player, tutorialId);
                return;
            }
            TutorialManager.tutorialProgress.put(uuid, nextIndex);
            showStep(player, tutorialId, nextIndex);
        }, 40L);
    }

    private void showStep(Player player, String tutorialId, int index) {
        List<TutorialStepData> steps = plugin.getTutorialJsonManager().getSteps(tutorialId);
        if (steps == null || index < 0 || index >= steps.size()) return;
        TutorialStepData step = steps.get(index);
        UUID uuid = player.getUniqueId();
        boolean grantItems = grantItemsMap.getOrDefault(uuid, true);
        // 再起動・リロード前に生成されて残った表示もUUIDタグから除去する
        clearPlayerDisplays(uuid);

        // 1. 転移(★変更: 複数人が同じ座標に重ならないよう、他の要素と同じスロットでオフセットする)
        if (step.teleport != null) {
            Location baseTpLoc = step.teleport.toBukkitLocation();
            Location tpLoc = offsetLocation(baseTpLoc, uuid);
            if (tpLoc != null) player.teleport(tpLoc);
        }

        // 2. ダメージ判定(null=現状維持)
        boarCombatPlayers.remove(uuid);
        if ("frenzy_boar".equalsIgnoreCase(step.mobId)) {
            boarCombatPlayers.add(uuid);
            // モブのターゲット選択を妨げないよう、バニラの無敵状態は使用しない。
            // ボアのダメージだけをSAODamageListener/SAOMobSkillExecutorで無効化する。
            player.setInvulnerable(false);
        } else if (step.damage != null) {
            player.setInvulnerable(!step.damage);
        }

        // 3. アイテム付与(★NPCからの再受講時はスキップする)
        if (grantItems && step.items != null) {
            for (Map.Entry<String, Integer> entry : step.items.entrySet()) {
                ItemStack itemToGive = resolveTutorialItem(entry.getKey());
                if (itemToGive != null) {
                    itemToGive.setAmount(Math.max(1, entry.getValue()));
                    player.getInventory().addItem(itemToGive);
                }
            }
        }

        // 3.5. モブスポーン(mobId・mobLocationが両方指定されている場合のみ、1体だけ)
        // ★変更: 複数人同時進行時に座標が重ならないよう、プレイヤーごとのスロットでオフセットする。
        // また、このモブを倒してもEXP/Col/アイテムが入らないよう専用タグを付け、
        // 次のステップへ進む時・終了時に確実に消せるよう所有者タグも付ける。
        if (step.mobId != null && !step.mobId.isEmpty() && step.mobLocation != null) {
            Location baseMobLoc = step.mobLocation.toBukkitLocation();
            Location mobLoc = offsetLocation(baseMobLoc, uuid);
            if (mobLoc != null) {
                plugin.getSaoMobManager().spawnMob(step.mobId, mobLoc);
                // ★spawnMobはvoidを返すため、直後に近くのエンティティを探してタグを付け足す
                for (org.bukkit.entity.Entity nearby : mobLoc.getWorld().getNearbyEntities(mobLoc, 1.5, 1.5, 1.5)) {
                    if (nearby instanceof org.bukkit.entity.LivingEntity
                            && nearby.getScoreboardTags().contains("sao_mob_" + step.mobId)
                            && !nearby.getScoreboardTags().contains("sao_tutorial_mob")) {
                        nearby.addScoreboardTag("sao_tutorial_mob");
                        nearby.addScoreboardTag("sao_tut_owner_" + uuid);
                        break;
                    }
                }
            }
        }

        // 4. メッセージのテキストディスプレイ(★プレイヤーごとのスロットでオフセット)
        List<Entity> spawned = new ArrayList<>();
        if (step.message != null && !step.message.isEmpty() && step.messageLocation != null) {
            Location baseMsgLoc = step.messageLocation.toBukkitLocation();
            Location msgLoc = offsetLocation(baseMsgLoc, uuid);
            if (msgLoc != null) {
                TextDisplay msgDisplay = (TextDisplay) msgLoc.getWorld().spawnEntity(msgLoc, org.bukkit.entity.EntityType.TEXT_DISPLAY);
                msgDisplay.setText(String.join("\n", step.message));
                msgDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                msgDisplay.addScoreboardTag("sao_tutorial_message");
                msgDisplay.addScoreboardTag("sao_tut_owner_" + uuid);
                spawned.add(msgDisplay);
            }
        }

        // 5. 「次へ」ボタン(★プレイヤーごとのスロットでオフセット)
        if (step.nextButtonLocation != null) {
            Location baseNextLoc = step.nextButtonLocation.toBukkitLocation();
            Location nextLoc = offsetLocation(baseNextLoc, uuid);
            if (nextLoc != null) {
                TextDisplay nextDisplay = (TextDisplay) nextLoc.getWorld().spawnEntity(nextLoc, org.bukkit.entity.EntityType.TEXT_DISPLAY);
                nextDisplay.setText(step.endTutorial ? "§6[ 終了する ]" : "§e[ 次へ ]");
                nextDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                nextDisplay.addScoreboardTag(NEXT_TAG);
                nextDisplay.addScoreboardTag("sao_tut_owner_" + uuid);
                spawned.add(nextDisplay);

                Interaction interaction = (Interaction) nextLoc.getWorld().spawnEntity(nextLoc, org.bukkit.entity.EntityType.INTERACTION);
                interaction.setInteractionHeight(1.0f);
                interaction.setInteractionWidth(1.0f);
                interaction.setResponsive(true);
                interaction.addScoreboardTag(NEXT_TAG);
                interaction.addScoreboardTag("sao_tut_owner_" + uuid);
                spawned.add(interaction);
            }
        }

        activeDisplays.put(uuid, spawned);
    }

    private ItemStack resolveTutorialItem(String itemId) {
        // ★予約ID: 《Menu》アイテムはSAOItemManager経由では解決できないため特別扱いする
        if ("menu_item".equals(itemId)) {
            return TutorialManager.getSkillMenuItem();
        }

        sac.plugin.info.SACplugin.item.SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(itemId);
        if (weapon != null) return plugin.getSaoItemManager().createWeaponItem(weapon);

        sac.plugin.info.SACplugin.item.SAOArmor armor = plugin.getSaoItemManager().getArmor(itemId);
        if (armor != null) return plugin.getSaoItemManager().createArmorItem(armor);

        sac.plugin.info.SACplugin.item.SAOGeneralItemData general = plugin.getSaoItemManager().getGeneralItem(itemId);
        if (general != null) return plugin.getSaoItemManager().createGeneralItem(general);

        return null;
    }

    private void clearPlayerDisplays(UUID uuid) {
        List<Entity> list = activeDisplays.remove(uuid);
        if (list != null) {
            for (Entity e : list) {
                if (e != null && !e.isDead()) e.remove();
            }
        }
        // ワールドに保存された古いTextDisplay/Interactionも削除（再起動・再接続対策）
        String ownerTag = "sao_tut_owner_" + uuid;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getScoreboardTags().contains(ownerTag)
                        && (e instanceof TextDisplay || e instanceof Interaction)) e.remove();
            }
        }
        // ★追加: このプレイヤー専用のチュートリアルモブが残っていれば一緒に消す
        // (ステップが進む・終了する・中断するたびに、練習台のモブが積み上がらないようにする)
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : world.getEntitiesByClass(org.bukkit.entity.LivingEntity.class)) {
                if (e.getScoreboardTags().contains("sao_tutorial_mob") && e.getScoreboardTags().contains(ownerTag)) {
                    e.remove();
                }
            }
        }
    }

    /**
     * チュートリアル終了処理。
     * 無敵解除・Menu/転移結晶以外のアイテム削除・完了記録を行う。
     */
    private void endTutorial(Player player, String tutorialId) {
        UUID uuid = player.getUniqueId();
        pendingTransitions.remove(uuid);
        boarCombatPlayers.remove(uuid);
        clearPlayerDisplays(uuid);

        player.setInvulnerable(false);

        PlayerData stats = plugin.getPlayerManager().getStats(player);

        // ==========================================
        // ★追加: 既にレベルアップしている(=フレッシュな新規プレイヤーではない)場合は、
        // 再受講などで持ち物を丸ごと消してしまわないよう、削除処理をスキップする。
        // ==========================================
        boolean shouldClearInventory = (stats == null || stats.level < 2);
        if (shouldClearInventory) {
            // Menu・転移結晶以外のアイテムを削除
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                if (item == null || item.getType() == org.bukkit.Material.AIR) continue;
                if (isMenuOrCrystal(item)) continue;
                player.getInventory().setItem(i, null);
            }
        }

        if (stats != null && !stats.completedTutorials.contains(tutorialId)) {
            stats.completedTutorials.add(tutorialId);
            plugin.getPlayerManager().savePlayerData(player, stats);
        }

        boarCombatPlayers.remove(uuid);
        TutorialManager.activeTutorial.remove(uuid);
        TutorialManager.tutorialProgress.remove(uuid);
        releaseSlot(uuid);
        grantItemsMap.remove(uuid);

        player.sendMessage("§a[System] 「" + tutorialId + "」のチュートリアルが終了しました！");
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    /** チュートリアルを「中断」する。完了扱いにはせず、表示・無敵状態だけ片付ける。 */
    public void cancelTutorial(Player player) {
        UUID uuid = player.getUniqueId();
        pendingTransitions.remove(uuid);
        boarCombatPlayers.remove(uuid);
        clearPlayerDisplays(uuid);
        player.setInvulnerable(false);
        boarCombatPlayers.remove(uuid);
        TutorialManager.activeTutorial.remove(uuid);
        TutorialManager.tutorialProgress.remove(uuid);
        releaseSlot(uuid);
        grantItemsMap.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingTransitions.remove(uuid);
        boarCombatPlayers.remove(uuid);
        clearPlayerDisplays(uuid);
        releaseSlot(uuid);
        grantItemsMap.remove(uuid);
        boarCombatPlayers.remove(uuid);
        TutorialManager.activeTutorial.remove(uuid);
        TutorialManager.tutorialProgress.remove(uuid);
        event.getPlayer().setInvulnerable(false);
    }

    private boolean isMenuOrCrystal(ItemStack item) {
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
        String name = item.getItemMeta().getDisplayName();
        if (name.equals("§b《Menu》")) return true;
        if (item.getItemMeta().hasLore() && !item.getItemMeta().getLore().isEmpty()) {
            String idLine = org.bukkit.ChatColor.stripColor(item.getItemMeta().getLore().get(0));
            return "tp_crystal".equals(idLine);
        }
        return false;
    }
}