package sac.plugin.info.SACplugin.command;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.Event.SAOAGIListener;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.item.SAOGeneralItemData;
import sac.plugin.info.SACplugin.item.SAOWeapon;
import sac.plugin.info.SACplugin.shop.PlayerShopManager;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SAOCommand implements CommandExecutor, org.bukkit.command.TabCompleter {
    private final SACplugin plugin;

    public SAOCommand(SACplugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 実行者がプレイヤーであり、かつOP（管理者）でない場合は空のリストを返す
        // これにより、一般プレイヤーのチャット欄には候補が表示されなくなります
        if (command.getName().equalsIgnoreCase("sac")) {
            if (!(sender instanceof Player) || !sender.isOp()) {
                return Collections.emptyList();
            }
        }

        // OP（管理者）の場合は通常通りの補完（nullを返すとデフォルトの挙動）を許可
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        PlayerData stats = plugin.getPlayerManager().getStats(player);

        if (stats == null) return true;

        // --- 1. /skillpoint コマンドの処理 ---
        if (label.equalsIgnoreCase("skillpoint")) {
            showSkillMenu(player, stats);
            return true;
        }

        // --- 2. メインコマンド (/sac) の引数がない場合 ---
        if (args.length == 0) {
            player.sendMessage("§b[System] §r§c使用法: /sac <give/spawn/add>");
            return true;
        }

        String sub = args[0].toLowerCase();

        // --- 0. time 処理（OP限定: サーバー内時刻を操作） ---
        if (sub.equalsIgnoreCase("time")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /sac time <set|add> <時刻 or 増減時間>");
                player.sendMessage("§b[System] §r§7例: /sac time set 4:00　/sac time set 18　/sac time add 2");
                return true;
            }

            String timeSub = args[1].toLowerCase();

            if (timeSub.equals("set")) {
                if (args.length < 3) {
                    player.sendMessage("§b[System] §r§c使用法: /sac time set <HH:MM または HH>");
                    return true;
                }
                Double hour = parseHour(args[2]);
                if (hour == null) {
                    player.sendMessage("§b[System] §r§c時刻の形式が正しくありません。例: 4:00, 18, 13:30");
                    return true;
                }
                plugin.getTimeManager().setGameHour(hour);
                player.sendMessage("§a[System] サーバー内時刻を §e" + plugin.getTimeManager().getGameTimeString() + " §aに設定しました。");
                return true;

            } else if (timeSub.equals("add")) {
                if (args.length < 3) {
                    player.sendMessage("§b[System] §r§c使用法: /sac time add <増減する時間(時間単位、小数可)>");
                    return true;
                }
                double delta;
                try {
                    delta = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage("§b[System] §r§c数値を入力してください。例: /sac time add 2 または /sac time add -1.5");
                    return true;
                }
                double currentHour = parseHour(plugin.getTimeManager().getGameTimeString());
                plugin.getTimeManager().setGameHour(currentHour + delta);
                player.sendMessage("§a[System] サーバー内時刻を §e" + plugin.getTimeManager().getGameTimeString() + " §aに変更しました。");
                return true;

            } else {
                player.sendMessage("§b[System] §r§c使用法: /sac time <set|add> <時刻 or 増減時間>");
                return true;
            }
        }

        // --- A. add 処理 ---
        if (sub.equalsIgnoreCase("add")) {
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /sac add <str/vit/dex/agi>");
                return true;
            }

            if (stats.skillPoint <= 0) {
                player.sendMessage("§b[System] §r§cスキルポイントが足りません。");
                return true;
            }

            String type = args[1].toLowerCase();
            switch (type) {
                case "str":
                    stats.str++;
                    player.sendMessage("§b[System] §r§6STR（筋力値）§fが上昇！");
                    break;
                case "agi":
                    stats.agi++;
                    new SAOAGIListener(plugin).updateMovementSpeed(player);
                    player.sendMessage("§b[System] §r§bAGI（敏捷値）§fが上昇！");
                    break;
                default:
                    player.sendMessage("§b[System] §r§c無効なステータス名です。");
                    return true;
            }

            stats.skillPoint--;
            plugin.getSaoScoreboard().updateScoreboard(player, stats);
            plugin.getPlayerManager().savePlayerData(player, stats);

            if (stats.skillPoint > 0) showSkillMenu(player, stats);
            return true;
        }

        // --- B. give 処理 ---
        if (sub.equalsIgnoreCase("give")) {
            if (!player.isOp()) {
                player.sendMessage("§c[SAO] アイテムを呼び出すPERMISSION DENIED。");
                return true;
            }
            // 引数が3つ（個数指定なし）か4つ（個数指定あり）必要
            if (args.length < 3) {
                player.sendMessage("§b[System] §r§c使い方: /sac give <プレイヤー名> <ID> [個数]");
                player.sendMessage("§b[System] §r§c武器/防具(自分に付与): /sac give <ID> <個数> <成功値> <強化値>");
                return true;
            }

            // ==========================================
            // ★追加: 武器/防具専用の構文。
            // /sac give <武器orID> <個数> <成功値:生産成功/大成功/奇跡的成功/無し> <強化値>
            // 成功ボーナス・強化のステ振りはここでは行わず、鍛造台で自由に割り振れる
            // 「未割り振りポイント」として付与する(既存の鍛造引継ぎ移行と同じ考え方)。
            // 常にコマンド実行者自身に付与する。
            // ==========================================
            SAOWeapon giveTargetWeapon = plugin.getSaoItemManager().getWeapon(args[1]);
            sac.plugin.info.SACplugin.item.SAOArmor giveTargetArmor = plugin.getSaoItemManager().getArmor(args[1]);
            if ((giveTargetWeapon != null || giveTargetArmor != null) && args.length >= 5) {
                giveForgedItem(player, args[1], args[2], args[3], args[4], giveTargetWeapon, giveTargetArmor);
                return true;
            }

            int amount = 1; // デフォルトは1個
            if (args.length >= 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    player.sendMessage("§c[SAO] 個数は数値で指定してください。");
                    return true;
                }
            }

            giveAnyItem(player, args[1], args[2], amount);
            return true;
        }

        // --- C. spawn 処理 ---
        if (sub.equalsIgnoreCase("spawn")) {
            if (!player.isOp()) {
                player.sendMessage("§c[SAO] スポナーを呼び出すPERMISSION DENIED。");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使い方: /sac spawn <モンスターID>");
                return true;
            }
            plugin.getSaoMobManager().spawnMob(args[1], player.getLocation());
            player.sendMessage("§b[System] §r§aモンスター [" + args[1] + "] を召喚しました！");
            return true;
        }
        // ==========================================
        // ★ 修正: NPCショップ設置とプレイヤーショップ開店の統合
        // ==========================================
        if (sub.equalsIgnoreCase("shop")) {
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /sac shop <NPCショップID> または /sac shop open <店舗名>");
                return true;
            }

            // --- A. プレイヤーショップの開店処理 (/sac shop open 店舗名) ---
            if (args[1].equalsIgnoreCase("open")) {
                if (args.length < 3) {
                    player.sendMessage("§b[System] §r§c使用法: /sac shop open <店舗名>");
                    return true;
                }

                // スキルチェック (既に取得済みの stats を使用)
                boolean hasCommerce = false;
                if (stats.equippedSkills != null) {
                    for (String s : stats.equippedSkills) {
                        if ("商業".equals(s)) { hasCommerce = true; break; }
                    }
                }

                if (!hasCommerce) {
                    player.sendMessage("§b[System] §r§cショップを開くにはスキルスロットに「商業」をセットしてください！");
                    return true;
                }

                // ★ 1プレイヤー1店舗チェック：全ワールドで既に出店中か確認
                boolean alreadyHasShop = false;
                for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
                    for (org.bukkit.entity.Entity e : w.getEntities()) {
                        if (!(e instanceof org.bukkit.entity.Interaction)) continue;
                        if (!e.getScoreboardTags().contains("sao_player_shop")) continue;
                        sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop s =
                                plugin.getPlayerShopManager().getShop(e.getUniqueId());
                        if (s != null && s.ownerUuid.equals(player.getUniqueId())) {
                            alreadyHasShop = true;
                            break;
                        }
                    }
                    if (alreadyHasShop) break;
                }
                if (alreadyHasShop) {
                    player.sendMessage("§b[System] §r§c既に店舗を出店しています！新しく開店するには先に §e/sac shop close §cで閉店してください。");
                    return true;
                }

                String shopName = args[2];

                // 周囲の「Shop Spot」を探す
                org.bukkit.entity.ArmorStand targetSpot = null;
                for (org.bukkit.entity.Entity e : player.getNearbyEntities(5, 5, 5)) {
                    if (e instanceof org.bukkit.entity.ArmorStand && e.getCustomName() != null && e.getCustomName().contains("Shop Spot")) {
                        targetSpot = (org.bukkit.entity.ArmorStand) e;
                        break;
                    }
                }

                if (targetSpot == null) {
                    player.sendMessage("§b[System] §r§c近くに「Shop Spot」が見つかりません。(空き店舗の中央で実行してください)");
                    return true;
                }

                // 見つかったSpotをショップに変換
                // ★ targetSpot は撤去して BlockDisplay+TextDisplay+Interaction 方式に切り替え
                targetSpot.remove();

                // ★ Shop Spot の足元座標をそのまま使う
                org.bukkit.Location centerLoc = targetSpot.getLocation().clone();
                centerLoc.setX(centerLoc.getBlockX() + 0.5);
                centerLoc.setY(centerLoc.getBlockY());
                centerLoc.setZ(centerLoc.getBlockZ() + 0.5);
                // ★追加: 元のlocationが持つyaw/pitchが意図せず影響している可能性があるため、明示的に0にリセットする
                centerLoc.setYaw(0f);
                centerLoc.setPitch(0f);

                // 1. 本棚の見た目 (BlockDisplay) ── プレイヤーの向きに合わせて正面を設定
                org.bukkit.entity.BlockDisplay blockDisplay = (org.bukkit.entity.BlockDisplay)
                        player.getWorld().spawnEntity(centerLoc, org.bukkit.entity.EntityType.BLOCK_DISPLAY);
                // ★追加: スポーン直後の補間によるズレ・傾きを防ぐ(Multiverse等の別ワールドで顕著)
                blockDisplay.setInterpolationDuration(0);
                blockDisplay.setTeleportDuration(0);

                org.bukkit.block.data.BlockData chestData = org.bukkit.Bukkit.createBlockData(org.bukkit.Material.CHISELED_BOOKSHELF);
                if (chestData instanceof org.bukkit.block.data.Directional) {
                    org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) chestData;
                    float yaw = (player.getLocation().getYaw() % 360 + 360) % 360;
                    org.bukkit.block.BlockFace face;
                    if (yaw >= 45 && yaw < 135)       face = org.bukkit.block.BlockFace.WEST;
                    else if (yaw >= 135 && yaw < 225) face = org.bukkit.block.BlockFace.NORTH;
                    else if (yaw >= 225 && yaw < 315) face = org.bukkit.block.BlockFace.EAST;
                    else                               face = org.bukkit.block.BlockFace.SOUTH;
                    directional.setFacing(face.getOppositeFace());
                    blockDisplay.setBlock(directional);
                } else {
                    blockDisplay.setBlock(chestData);
                }
                blockDisplay.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(-0.5f, 0, -0.5f),
                        new org.joml.Quaternionf(),
                        new org.joml.Vector3f(1, 1, 1),
                        new org.joml.Quaternionf()
                ));
                blockDisplay.addScoreboardTag("sao_shop_furniture");

                // 2. 店名の浮き文字 (TextDisplay)
                org.bukkit.entity.TextDisplay shopTextDisplay = (org.bukkit.entity.TextDisplay)
                        player.getWorld().spawnEntity(centerLoc.clone().add(0, 1.3, 0), org.bukkit.entity.EntityType.TEXT_DISPLAY);
                shopTextDisplay.setText("§e" + shopName);
                shopTextDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                shopTextDisplay.addScoreboardTag("sao_shop_furniture");

                // 3. クリック判定 (Interaction) ← これがショップのIDになる
                org.bukkit.entity.Interaction shopInteraction = (org.bukkit.entity.Interaction)
                        player.getWorld().spawnEntity(centerLoc, org.bukkit.entity.EntityType.INTERACTION);
                shopInteraction.setInteractionHeight(1.0f);
                shopInteraction.setInteractionWidth(1.0f);
                shopInteraction.setResponsive(true);
                shopInteraction.addScoreboardTag("sao_player_shop");

                // ショップデータを生成してマネージャーに登録 (IDは Interaction の UUID)
                sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop newShop = new sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop();
                newShop.shopId = shopInteraction.getUniqueId();
                newShop.ownerUuid = player.getUniqueId();
                newShop.ownerName = player.getName();
                newShop.shopName = shopName;

                plugin.getPlayerShopManager().addShop(shopInteraction.getUniqueId(), newShop);

                player.sendMessage("§a[System] 店舗「" + shopName + "」をオープンしました！");
                player.sendMessage("§b[System] §r§f本棚を右クリックして商品を並べましょう！");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                return true;
            } // end if open

            if (args[1].equalsIgnoreCase("close")) {
                // ★ Interaction エンティティから自分のショップを探す
                org.bukkit.entity.Interaction targetShop = null;
                sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop myShop = null;

                for (org.bukkit.entity.Entity e : player.getNearbyEntities(5, 5, 5)) {
                    if (e instanceof org.bukkit.entity.Interaction && e.getScoreboardTags().contains("sao_player_shop")) {
                        sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop s = plugin.getPlayerShopManager().getShop(e.getUniqueId());
                        if (s != null && s.ownerUuid.equals(player.getUniqueId())) {
                            targetShop = (org.bukkit.entity.Interaction) e;
                            myShop = s;
                            break;
                        }
                    }
                }

                if (targetShop == null) {
                    player.sendMessage("§b[System] §r§c近くにあなたの開いているショップが見つかりません。");
                    return true;
                }

                // 1. 商品をインベントリに返却
                for (sac.plugin.info.SACplugin.shop.PlayerShopManager.ShopItem shopItem : myShop.items.values()) {
                    java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> leftovers = player.getInventory().addItem(shopItem.item);
                    for (org.bukkit.inventory.ItemStack leftover : leftovers.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
                myShop.items.clear();

                // 2. 売上金と経験値の受け取り
                Long pendingCol = plugin.getPlayerShopManager().pendingSales.remove(player.getUniqueId());
                Long pendingExp = plugin.getPlayerShopManager().pendingExp.remove(player.getUniqueId());

                if (pendingCol != null && pendingCol > 0) {
                    stats.col += pendingCol;
                    player.sendMessage("§e[System] 未受け取りの売上 §a" + pendingCol + " Col §eを受け取りました！");
                }
                if (pendingExp != null && pendingExp > 0) {
                    int currentExp = stats.proficiencyExp.getOrDefault("Commerce", 0) + pendingExp.intValue();
                    int currentProf = stats.proficiency.getOrDefault("Commerce", 1);
                    boolean leveledUp = false;
                    int requiredExp = currentProf * 5000;

                    while (currentExp >= requiredExp) {
                        currentExp -= requiredExp;
                        currentProf++;
                        requiredExp = currentProf * 5000;
                        leveledUp = true;
                    }

                    stats.proficiencyExp.put("Commerce", currentExp);

                    if (leveledUp) {
                        stats.proficiency.put("Commerce", currentProf);
                        player.sendMessage("§b[SKILL UP] §f商業スキルの熟練度が §e" + currentProf + " §fに上がりました！");
                        player.sendMessage("§b[System] §r§aショップに並べられる商品の数が §e" + (currentProf + 2) + "枠 §aに増えました！");
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    }
                }

                // データの保存とスコアボード更新
                plugin.getPlayerManager().savePlayerData(player, stats);
                if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

                // 3. ★ Interaction と周辺の sao_shop_furniture を撤去し、Shop Spot を復元
                for (org.bukkit.entity.Entity nearby : targetShop.getNearbyEntities(2.0, 3.0, 2.0)) {
                    if (nearby.getScoreboardTags().contains("sao_shop_furniture")) {
                        nearby.remove();
                    }
                }
                org.bukkit.entity.ArmorStand newSpot = targetShop.getWorld().spawn(targetShop.getLocation(), org.bukkit.entity.ArmorStand.class);
                newSpot.setVisible(false);
                newSpot.setGravity(false);
                newSpot.setInvulnerable(true);
                newSpot.setCustomName("§bShop Spot");
                newSpot.setCustomNameVisible(true);
                newSpot.addScoreboardTag("sao_shop_spot");
                targetShop.remove();

                // 4. ショップデータの削除
                plugin.getPlayerShopManager().removeShop(targetShop.getUniqueId());

                player.sendMessage("§c[System] ショップを閉店しました。未売却のアイテムはインベントリに戻りました。");
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_CHEST_CLOSE, 1f, 1f);

                return true;
            }

            // --- B. 既存のNPCショップ設置処理 (/sac shop <ショップID>) ---
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cNPCショップを設置するPERMISSION DENIED。");
                return true;
            }

            String shopId = args[1];
            sac.plugin.info.SACplugin.item.SAOShopData shopData = plugin.getSaoItemManager().getShop(shopId);

            String customName = "ショップ"; // デフォルト名
            boolean isSellShop = shopId.equalsIgnoreCase("sell_shop");

            if (!isSellShop) {
                if (shopData == null) {
                    player.sendMessage("§b[System] §r§cショップID '" + shopId + "' は存在しません。");
                    return true;
                }
                customName = shopData.name;
            } else {
                customName = "《買取屋》";
            }

            // 村人の生成
            org.bukkit.entity.Villager shopkeeper = player.getWorld().spawn(player.getLocation(), org.bukkit.entity.Villager.class);
            shopkeeper.setAI(false);
            shopkeeper.setInvulnerable(true);
            shopkeeper.setSilent(true);
            shopkeeper.setCustomNameVisible(true);
            shopkeeper.setCustomName(customName);

            if (isSellShop) {
                shopkeeper.addScoreboardTag("sao_shop_sell");
                player.sendMessage("§a[SAO] 買取屋を設置しました。");
            } else {
                shopkeeper.addScoreboardTag("sao_shop_" + shopId);
                player.sendMessage("§a[SAO] " + customName + " (" + shopId + ") を設置しました。");
            }
            return true;
        }

        if (sub.equalsIgnoreCase("spawner")) {
            if (!player.isOp()) {
                player.sendMessage("§c[SAO] スポナー設定を変更するPERMISSION DENIED。");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /sac spawner <on/off>");
                return true;
            }
            if (args[1].equalsIgnoreCase("on")) {
                plugin.setSpawnerEnabled(true);
                player.sendMessage("§a[SAO] スポナー機能をONにしました。");
            } else {
                plugin.setSpawnerEnabled(false);
                player.sendMessage("§c[SAO] スポナー機能をOFFにしました。");
            }
            return true;
        }
        // onCommand 内の subCommand 判定に追加
        if (sub.equalsIgnoreCase("getspawner")) {
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /sac getspawner <モンスターID>");
                return true;
            }

            String mobId = args[1].toLowerCase();

            // アーマースタンドのアイテムを作成
            org.bukkit.inventory.ItemStack spawnerItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ARMOR_STAND);
            org.bukkit.inventory.meta.ItemMeta meta = spawnerItem.getItemMeta();

            if (meta != null) {
                meta.setDisplayName("§bSAO Spawner: §e" + mobId);
                List<String> lore = new ArrayList<>();
                lore.add("§7設置すると、この場所に §f" + mobId + " §7が湧きます。");
                // NBTタグの代わりに説明文（Lore）にIDを仕込むか、直接タグを保持させる
                meta.setLore(lore);
                spawnerItem.setItemMeta(meta);
            }

            player.getInventory().addItem(spawnerItem);
            player.sendMessage("§a[SAO] §e" + mobId + " §f専用のスポナーを受け取りました。");
            return true;
        }

        // ==========================================
        // ★ 追加: Tutorial NPC の設置
        // ==========================================
        if (sub.equalsIgnoreCase("tutorialnpc")) {
            if (!player.isOp()) return true;

            org.bukkit.entity.Villager npc = player.getWorld().spawn(player.getLocation(), org.bukkit.entity.Villager.class);
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setCustomNameVisible(true);
            npc.setCustomName("§eTutorial NPC");

            // 下に説明文を出すための透明なArmorStand
            org.bukkit.entity.ArmorStand text = player.getWorld().spawn(player.getLocation().add(0, -0.5, 0), org.bukkit.entity.ArmorStand.class);
            text.setVisible(false);
            text.setGravity(false);
            text.setInvulnerable(true);
            text.setCustomName("§f<右クリックでチュートリアル開始>");
            text.setCustomNameVisible(true);
            text.addScoreboardTag("sao_tutorial_text");

            npc.addScoreboardTag("sao_tutorial_npc");
            player.sendMessage("§a[System] 足元にチュートリアルNPCを設置しました。");
            return true;
        }

        // ==========================================
        // ★追加: クエストNPC管理GUIを開く
        // ==========================================
        if (args[0].equalsIgnoreCase("qnpc")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§b[System] §rプレイヤーからのみ実行可能です。");
                return true;
            }

            if (!player.hasPermission("sao.admin")) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }
            plugin.getQuestManager().openQuestFloorMenu(player);
            return true;
        }

        // ==========================================
        // ★追加: 既存のクエストNPCをシステムに同期する
        // ==========================================
        if (args[0].equalsIgnoreCase("qnpc_sync")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§b[System] §rプレイヤーからのみ実行可能です。");
                return true;
            }
            if (!player.hasPermission("sao.admin")) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }

            // プレイヤーの周囲「50ブロック」以内のNPCを探して同期
            int count = plugin.getQuestManager().syncNearbyOldNPCs(player, 50.0);

            if (count > 0) {
                player.sendMessage("§a[SAO] 周辺の " + count + "体の既存NPCを発見し、最新システムに同期しました！");
            } else {
                player.sendMessage("§e[SAO] 周囲50ブロックに未同期のNPCは見つかりませんでした。");
            }
            return true;
        }

        if (sub.equalsIgnoreCase("setupforge")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }
            // プレイヤーの足元に設置
            plugin.getForgeManager().spawnForgeStation(player.getLocation());

            player.sendMessage("§a[SAO] §f鍛造ステーションを設置しました。");
            return true;
        }
        if (sub.equalsIgnoreCase("removeforge")) {
            if (!sender.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }

            // 足元のステーションを撤去
            plugin.getForgeManager().removeForgeStation(player.getLocation());

            player.sendMessage("§e[SAO] §f周囲の鍛造ステーションを撤去しました。");
            return true;

        }
        // --- ★ 追加: 調理台の設置 ---
        if (sub.equalsIgnoreCase("setupcooking")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }
            // プレイヤーの足元に設置
            plugin.getCookingManager().spawnCookingStation(player.getLocation());

            player.sendMessage("§a[SAO] §f調理台を設置しました。");
            return true;
        }

        // --- ★ 追加: 調理台の撤去 ---
        if (sub.equalsIgnoreCase("removecooking")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }
            // 足元の調理台を撤去
            plugin.getCookingManager().removeCookingStation(player.getLocation());

            player.sendMessage("§e[SAO] §f周囲の調理台を撤去しました。");
            return true;
        }

        // ==========================================
        // ★ 追加: ストレージの設置
        // ==========================================
        if (sub.equalsIgnoreCase("setupstorage")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }
            // プレイヤーの足元に設置
            plugin.getStorageManager().spawnStorageStation(player.getLocation());

            player.sendMessage("§a[SAO] §fストレージを設置しました。");
            return true;
        }

        // ==========================================
        // ★ 追加: ストレージの撤去
        // ==========================================
        if (sub.equalsIgnoreCase("removestorage")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }
            // 足元のストレージを撤去
            plugin.getStorageManager().removeStorageStation(player.getLocation());

            player.sendMessage("§e[SAO] §f周囲のストレージを撤去しました。");
            return true;
        }

        // ==========================================
        // ★ 追加: 釣りNPCの設置
        // ==========================================
        if (sub.equalsIgnoreCase("fishnpc")) {
            if (!player.isOp()) return true;
            Villager npc = player.getWorld().spawn(player.getLocation(), Villager.class);
            npc.setAI(false); npc.setInvulnerable(true); npc.setSilent(true);
            npc.setCustomNameVisible(true); npc.setCustomName("§b釣りおじさん");
            npc.addScoreboardTag("sao_fishing_npc"); // 後日ショップ化するためのタグ
            player.sendMessage("§a[SAO] 釣りNPCを設置しました。");
            return true;
        }

        // ==========================================
        // ★ 追加: 釣りポイント(透明なアーマースタンド)の設置
        // ==========================================
        if (sub.equalsIgnoreCase("fishpoint")) {
            if (!player.isOp()) return true;
            org.bukkit.entity.ArmorStand stand = player.getWorld().spawn(player.getLocation(), org.bukkit.entity.ArmorStand.class);
            stand.setVisible(false); // 本体は透明化
            stand.setGravity(false);
            stand.setInvulnerable(true);

            // ==========================================
            // ★追加: これを忘れていました！浮き文字を表示する設定
            // ==========================================
            stand.setCustomName("§bFishing Point");
            stand.setCustomNameVisible(true);

            stand.addScoreboardTag("Fishing Point"); // 判定用タグ
            player.sendMessage("§a[SAO] 釣りポイントを足元に設置しました！");
            return true;
        }
        // ==========================================
        // ★ 新規追加: 釣りポイントの撤去コマンド
        // ==========================================
        if (sub.equalsIgnoreCase("removefishpoint")) {
            if (!player.isOp()) return true;

            int removedCount = 0;
            // プレイヤーの周囲5ブロック以内にあるエンティティを探す
            for (org.bukkit.entity.Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 5.0, 5.0, 5.0)) {
                // アーマースタンドかつ「Fishing Point」タグを持っているか
                if (entity instanceof org.bukkit.entity.ArmorStand && entity.getScoreboardTags().contains("Fishing Point")) {
                    entity.remove();
                    // 消えたのが視覚的にわかるように煙のパーティクルを出す
                    entity.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, entity.getLocation().add(0, 1, 0), 10, 0.2, 0.2, 0.2, 0.05);
                    removedCount++;
                }
            }

            if (removedCount > 0) {
                player.sendMessage("§e[SAO] 周囲の釣りポイントを " + removedCount + " 箇所撤去しました！");
            } else {
                player.sendMessage("§c[SAO] 周囲(半径5ブロック以内)に釣りポイントは見つかりませんでした。");
            }
            return true;
        }

        // ==========================================
        // ★ 追加: Farm Point と農地の設置 (トウヒの柵付き)
        // ==========================================
        if (sub.equalsIgnoreCase("setupfarm")) {
            if (!player.isOp()) return true;

            org.bukkit.Location centerLoc = player.getLocation().getBlock().getLocation();

            // 1. 5x5 の耕地を生成 (Y-1)
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    org.bukkit.block.Block b = centerLoc.clone().add(x, -1, z).getBlock();
                    b.setType(org.bukkit.Material.FARMLAND);
                    org.bukkit.block.data.type.Farmland farmland = (org.bukkit.block.data.type.Farmland) b.getBlockData();
                    farmland.setMoisture(0);
                    b.setBlockData(farmland);
                }
            }

            // 2. 周囲(7x7の枠)にトウヒの柵を設置 (足元 Y)
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    // 外枠の座標のみに柵を置く
                    if (x == -3 || x == 3 || z == -3 || z == 3) {
                        org.bukkit.block.Block fenceBlock = centerLoc.clone().add(x, 0, z).getBlock();
                        fenceBlock.setType(org.bukkit.Material.SPRUCE_FENCE);
                    }
                }
            }

            // 3. Farm Point の浮き文字を設置
            org.bukkit.Location textLoc = centerLoc.clone().add(0.5, 1.0, 0.5);
            org.bukkit.entity.ArmorStand stand = player.getWorld().spawn(textLoc, org.bukkit.entity.ArmorStand.class);
            stand.setVisible(false); stand.setGravity(false); stand.setInvulnerable(true);
            stand.setCustomName("§bFarm Point"); stand.setCustomNameVisible(true);
            stand.addScoreboardTag("sao_farm_point");

            player.sendMessage("§a[SAO] 柵で囲った農地を設置しました！");
            return true;
        }

        // ==========================================
        // ★ 追加: Farm Point の撤去コマンド
        // ==========================================
        if (sub.equalsIgnoreCase("removefarm")) {
            if (!player.isOp()) return true;

            int removedCount = 0;
            // 周囲5ブロック以内の Farm Point を探す
            for (org.bukkit.entity.Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 5.0, 5.0, 5.0)) {
                if (entity instanceof org.bukkit.entity.ArmorStand && entity.getScoreboardTags().contains("sao_farm_point")) {
                    // 1. 畑のブロックと作物を物理的にリセット
                    plugin.getFarmManager().resetPhysicalFarm(entity.getUniqueId().toString());
                    // 2. 契約データをファイルから削除
                    plugin.getFarmManager().removeFarm(entity.getUniqueId().toString());
                    // 3. 浮き文字(アーマースタンド)を消去
                    entity.remove();
                    removedCount++;
                }
            }

            if (removedCount > 0) {
                player.sendMessage("§e[SAO] 周囲のファームポイントを " + removedCount + " 箇所撤去し、農地を更地に戻しました。");
            } else {
                player.sendMessage("§c[SAO] 周囲に撤去可能なファームポイントが見つかりませんでした。");
            }
            return true;
        }

        // ==========================================
        // ★ 追加: Shop Spot (空き店舗) の設置
        // ==========================================
        if (sub.equalsIgnoreCase("setupshopspot")) {
            if (!player.isOp()) return true;

            org.bukkit.entity.ArmorStand stand = player.getWorld().spawn(player.getLocation(), org.bukkit.entity.ArmorStand.class);
            stand.setVisible(false); // 本体は透明化
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setCustomName("§bShop Spot");
            stand.setCustomNameVisible(true);
            stand.addScoreboardTag("sao_shop_spot"); // 開店コマンドで検知するためのタグ

            player.sendMessage("§a[SAO] 足元に「Shop Spot」を設置しました！");
            return true;
        }

        // ==========================================
        // ★ 追加: Shop Spot (および開店中の店舗) の撤去
        // ==========================================
        if (sub.equalsIgnoreCase("removeshopspot")) {
            if (!player.isOp()) return true;

            int removedCount = 0;
            java.util.Set<org.bukkit.entity.Entity> toRemove = new java.util.HashSet<>();

            for (org.bukkit.entity.Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 5.0, 5.0, 5.0)) {

                // ★旧形式: ArmorStandベースのショップ
                if (entity instanceof org.bukkit.entity.ArmorStand) {
                    if (entity.getScoreboardTags().contains("sao_shop_spot")) {
                        toRemove.add(entity);
                    } else if (entity.getScoreboardTags().contains("sao_player_shop")) {
                        org.bukkit.block.Block block = entity.getLocation().clone().add(0, -1, 0).getBlock();
                        if (block.getType() == org.bukkit.Material.BARREL) block.setType(org.bukkit.Material.AIR);
                        for (org.bukkit.entity.Entity nearby : entity.getNearbyEntities(2.0, 3.0, 2.0)) {
                            if (nearby.getScoreboardTags().contains("sao_shop_furniture")) toRemove.add(nearby);
                        }
                        plugin.getPlayerShopManager().removeShop(entity.getUniqueId());
                        toRemove.add(entity);
                        removedCount++;
                    }
                }

                // ★新形式: Interactionベースのショップ
                if (entity instanceof org.bukkit.entity.Interaction
                        && entity.getScoreboardTags().contains("sao_player_shop")) {
                    // 周辺の家具エンティティ(BlockDisplay・TextDisplay)も削除対象に
                    for (org.bukkit.entity.Entity nearby : entity.getNearbyEntities(2.0, 3.0, 2.0)) {
                        if (nearby.getScoreboardTags().contains("sao_shop_furniture")) toRemove.add(nearby);
                    }
                    plugin.getPlayerShopManager().removeShop(entity.getUniqueId());
                    toRemove.add(entity);
                    removedCount++;
                }
            }

            // まとめて削除
            for (org.bukkit.entity.Entity e : toRemove) e.remove();

            if (removedCount > 0) {
                player.sendMessage("§e[SAO] 周囲の Shop Spot (店舗) を " + removedCount + " 箇所撤去しました。");
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f);
            } else {
                player.sendMessage("§c[SAO] 周囲(5ブロック以内)に撤去可能な店舗は見つかりませんでした。");
            }
            return true;
        }

        // ==========================================
        // ★ 追加: プレイヤーショップの閉店処理 (/sac shop close)
        // ==========================================
        if (args.length >= 2 && args[1].equalsIgnoreCase("close")) {
            // 自分のショップを探す
            org.bukkit.entity.ArmorStand targetShop = null;
            sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop myShop = null;

            for (org.bukkit.entity.Entity e : player.getNearbyEntities(5, 5, 5)) {
                if (e instanceof org.bukkit.entity.ArmorStand && e.getScoreboardTags().contains("sao_player_shop")) {
                    sac.plugin.info.SACplugin.shop.PlayerShopManager.PlayerShop s = plugin.getPlayerShopManager().getShop(e.getUniqueId());
                    // 自分がオーナーのショップか確認
                    if (s != null && s.ownerUuid.equals(player.getUniqueId())) {
                        targetShop = (org.bukkit.entity.ArmorStand) e;
                        myShop = s;
                        break;
                    }
                }
            }

            if (targetShop == null) {
                player.sendMessage("§b[System] §r§c近くにあなたの開いているショップが見つかりません。");
                return true;
            }

            // 1. 商品をインベントリに返却 (持ち物がいっぱいの場合は足元にドロップ)
            for (sac.plugin.info.SACplugin.shop.PlayerShopManager.ShopItem shopItem : myShop.items.values()) {
                java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> leftovers = player.getInventory().addItem(shopItem.item);
                for (org.bukkit.inventory.ItemStack leftover : leftovers.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
            myShop.items.clear();

            // 2. 売上金と経験値の受け取り
            Long pendingCol = plugin.getPlayerShopManager().pendingSales.remove(player.getUniqueId());
            Long pendingExp = plugin.getPlayerShopManager().pendingExp.remove(player.getUniqueId());

            if (pendingCol != null && pendingCol > 0) {
                stats.col += pendingCol;
                player.sendMessage("§e[System] 未受け取りの売上 §a" + pendingCol + " Col §eを受け取りました！");
            }
            if (pendingExp != null && pendingExp > 0) {
                int currentExp = stats.proficiencyExp.getOrDefault("Commerce", 0) + pendingExp.intValue();
                int currentProf = stats.proficiency.getOrDefault("Commerce", 1);
                boolean leveledUp = false;
                int requiredExp = currentProf * 5000;

                while (currentExp >= requiredExp) {
                    currentExp -= requiredExp;
                    currentProf++;
                    requiredExp = currentProf * 5000;
                    leveledUp = true;
                }

                stats.proficiencyExp.put("Commerce", currentExp);

                if (leveledUp) {
                    stats.proficiency.put("Commerce", currentProf);
                    player.sendMessage("§b[SKILL UP] §f商業スキルの熟練度が §e" + currentProf + " §fに上がりました！");
                    player.sendMessage("§b[System] §r§aショップに並べられる商品の数が §e" + (currentProf + 2) + "枠 §aに増えました！");
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                }
            }

            // データの保存とスコアボード更新
            plugin.getPlayerManager().savePlayerData(player, stats);
            if (plugin.getSaoScoreboard() != null) plugin.getSaoScoreboard().updateScoreboard(player, stats);

            // 3. エンティティの撤去と名前の復元
            targetShop.setCustomName("§bShop Spot");
            targetShop.getScoreboardTags().remove("sao_player_shop");
            targetShop.addScoreboardTag("sao_shop_spot");

            for (org.bukkit.entity.Entity nearby : targetShop.getNearbyEntities(2.0, 3.0, 2.0)) {
                if (nearby.getScoreboardTags().contains("sao_shop_furniture")) {
                    nearby.remove();
                }
            }

            // 4. ショップデータの削除
            plugin.getPlayerShopManager().removeShop(targetShop.getUniqueId());

            player.sendMessage("§c[System] ショップを閉店しました。未売却のアイテムはインベントリに戻りました。");
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_CHEST_CLOSE, 1f, 1f);

            return true;
        }

        if (sub.equalsIgnoreCase("npc")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cPERMISSION DENIED。");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /sac npc <クエストID>");
                return true;
            }

            String questId = args[1];
            // 作成済みの QuestManager からデータを取得
            sac.plugin.info.SACplugin.npc.SAOQuestData questData = plugin.getQuestManager().getQuest(questId);

            if (questData == null) {
                player.sendMessage("§b[System] §r§cクエストID '" + questId + "' は存在しません。");
                return true;
            }

            // 村人の生成
            Villager npc = player.getWorld().spawn(player.getLocation(), Villager.class);

            // NPCの設定 (動かない・無敵・取引不可)
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setCustomNameVisible(true);
            npc.setCustomName(questData.npcName); // JSONに設定したNPC名をセット

            // 取引画面が開かないように空のレシピをセット
            npc.setRecipes(new java.util.ArrayList<>());

            player.sendMessage("§a[SAO] クエストNPC '" + questData.npcName + "' を設置しました。");
            return true;
        }

        // --- D. setstats 処理 ---
        if (sub.equalsIgnoreCase("setstats")) {
            if (!player.isOp()) {
                player.sendMessage("§b[System] §r§cこのコマンドを実行するPERMISSION DENIED。");
                return true;
            }

            // 基本的な引数チェック (名前 と ステータス名 までは必須)
            if (args.length < 3) {
                player.sendMessage("§b[System] §r§c使用法: /sac setstats <プレイヤー名> <ステータス> <数値...>");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                player.sendMessage("§b[System] §r§cプレイヤーが見つかりません。");
                return true;
            }

            String statName = args[2].toLowerCase();
            PlayerData targetStats = plugin.getPlayerManager().getStats(target);
            if (targetStats == null) {
                player.sendMessage("§b[System] §r§cプレイヤーデータが存在しません。");
                return true;
            }

            // ここで一律 parseInt するのをやめ、各ケースの中で Parse するように変更
            switch (statName) {
                case "level":
                    if (args.length < 4) return true;
                    targetStats.level = Integer.parseInt(args[3]);
                    plugin.getPlayerManager().syncHealth(target, targetStats);
                    targetStats.currentHP = targetStats.maxHP;
                    target.setHealth(20.0);
                    break;

                case "str":
                    if (args.length < 4) return true;
                    targetStats.str = Integer.parseInt(args[3]);
                    break;

                case "agi":
                    if (args.length < 4) return true;
                    targetStats.agi = Integer.parseInt(args[3]);
                    // AGI更新時は target (コマンド対象) の速度を更新するように修正
                    new SAOAGIListener(plugin).updateMovementSpeed(target);
                    break;

                case "col":
                    if (args.length < 4) return true;
                    targetStats.col = Long.parseLong(args[3]);
                    break;

                case "skillpoint":
                    if (args.length < 4) return true;
                    targetStats.skillPoint = Integer.parseInt(args[3]);
                    break;

                case "proficiency":
                    // 熟練度は /sac setstats <名前> proficiency <カテゴリ> <数値> (引数5つ)
                    if (args.length < 5) {
                        player.sendMessage("§b[System] §r§c使用法: /sac setstats <名前> proficiency <カテゴリ> <数値>");
                        return true;
                    }
                    String category = args[3];
                    try {
                        int levelValue = Integer.parseInt(args[4]);
                        targetStats.proficiency.put(category, levelValue);
                        targetStats.usedDurability.put(category, 0); // 経験値リセット
                        player.sendMessage("§a[SAO] " + target.getName() + " の " + category + " 熟練度を " + levelValue + " にしました。");
                    } catch (NumberFormatException e) {
                        player.sendMessage("§b[System] §r§c熟練度の値は整数で入力してください。");
                        return true;
                    }
                    break;

                default:
                    player.sendMessage("§b[System] §r§c無効なステータス名: level, str, agi, col, skillpoint, proficiency");
                    return true;
            }

            // データの保存とスコアボードの更新
            plugin.getPlayerManager().savePlayerData(target, targetStats);
            plugin.getSaoScoreboard().updateScoreboard(target, targetStats);

            // 完了メッセージ (熟練度以外の場合)
            if (!statName.equals("proficiency")) {
                player.sendMessage("§a[SAO] " + target.getName() + " の " + statName + " を更新しました。");
            }
            target.sendMessage("§e[SAO] 管理者によってステータスが更新されました。");
            return true;
        }

        // ==========================================
        // ★ /sac tpm ── 転移門 (TextDisplay + Interaction) の設置
        // ==========================================
        if (sub.equalsIgnoreCase("tpm")) {
            if (!player.isOp()) { player.sendMessage("§b[System] §r§cPERMISSION DENIED。"); return true; }

            org.bukkit.Location loc = player.getLocation();
            loc.setX(loc.getBlockX() + 0.5);
            loc.setZ(loc.getBlockZ() + 0.5);

            // TextDisplay（浮き文字）
            org.bukkit.entity.TextDisplay textDisplay = (org.bukkit.entity.TextDisplay)
                    player.getWorld().spawnEntity(loc.clone().add(0, 1.5, 0), org.bukkit.entity.EntityType.TEXT_DISPLAY);
            textDisplay.setText("§b転移門");
            textDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            textDisplay.addScoreboardTag("sao_teleport_gate");

            // Interaction（クリック判定）
            org.bukkit.entity.Interaction interaction = (org.bukkit.entity.Interaction)
                    player.getWorld().spawnEntity(loc, org.bukkit.entity.EntityType.INTERACTION);
            interaction.setInteractionHeight(2.5f);
            interaction.setInteractionWidth(1.5f);
            interaction.setResponsive(true);
            interaction.addScoreboardTag("sao_teleport_gate");

            player.sendMessage("§a[SAO] 転移門を設置しました。右クリックで転移結晶と同じメニューが開きます。");
            return true;
        }

        // ==========================================
        // ★ /sac removetpm ── 周囲の転移門を撤去
        // ==========================================
        if (sub.equalsIgnoreCase("removetpm")) {
            if (!player.isOp()) { player.sendMessage("§b[System] §r§cPERMISSION DENIED。"); return true; }
            int count = 0;
            for (org.bukkit.entity.Entity e : player.getNearbyEntities(5, 5, 5)) {
                if (e.getScoreboardTags().contains("sao_teleport_gate")) {
                    e.remove();
                    count++;
                }
            }
            player.sendMessage(count > 0
                    ? "§e[SAO] 周囲の転移門を " + count + " 箇所撤去しました。"
                    : "§c周囲に転移門が見つかりませんでした。");
            return true;
        }

        // ==========================================
        // ==========================================
        // ★ /sac tp <入り口名|出口名> ── 圏内/圏外/ショートカットTPゲート設置
        // 名前にスペースを含む場合は | で区切る
        // 例: /sac tp 始まりの街へ|ウルバスへ
        // ==========================================
        if (sub.equalsIgnoreCase("tp")) {
            if (!player.isOp()) { player.sendMessage("§b[System] §r§cPERMISSION DENIED。"); return true; }
            if (args.length < 2) {
                player.sendMessage("§b[System] §r§c使用法: /sac tp <入り口名|出口名>");
                player.sendMessage("§b[System] §r§7例: /sac tp 始まりの街へ|ウルバスへ");
                return true;
            }

            // args[1]以降を全部結合して | で分割（スペース含む名前に対応）
            String combined = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            String[] parts = combined.split("\\|", 2);
            if (parts.length < 2) {
                player.sendMessage("§b[System] §r§c入り口名と出口名を § で区切ってください。");
                player.sendMessage("§b[System] §r§7例: /sac tp 始まりの街へ|ウルバスへ");
                return true;
            }

            String entryName = parts[0].trim();
            String exitName  = parts[1].trim();

            // プレイヤーの1ブロック前に設置
            org.bukkit.Location playerLoc = player.getLocation();
            double rad = Math.toRadians(playerLoc.getYaw());
            double dz  =  Math.cos(rad);
            double dx  = -Math.sin(rad);
            org.bukkit.Location standLoc = playerLoc.clone().add(dx, 0, dz);
            standLoc.setYaw(playerLoc.getYaw() + 180); // プレイヤーの方向を向く

            // TextDisplay（浮き文字）
            org.bukkit.entity.TextDisplay tpText = (org.bukkit.entity.TextDisplay)
                    player.getWorld().spawnEntity(standLoc.clone().add(0, 1.5, 0), org.bukkit.entity.EntityType.TEXT_DISPLAY);
            tpText.setText("§e" + entryName);
            tpText.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            tpText.addScoreboardTag("sao_tp_gate");

            // Interaction（進入判定）
            org.bukkit.entity.Interaction tpInteraction = (org.bukkit.entity.Interaction)
                    player.getWorld().spawnEntity(standLoc, org.bukkit.entity.EntityType.INTERACTION);
            tpInteraction.setInteractionHeight(2.0f);
            tpInteraction.setInteractionWidth(3.0f);
            tpInteraction.setResponsive(true);
            tpInteraction.addScoreboardTag("sao_tp_gate");

            // データを TpGateManager に保存
            sac.plugin.info.SACplugin.crystal.TpGateManager.TpGateData gateData =
                    new sac.plugin.info.SACplugin.crystal.TpGateManager.TpGateData();
            gateData.standUuid = tpInteraction.getUniqueId().toString();
            gateData.entryName = entryName;
            gateData.exitName  = exitName;
            gateData.worldName = standLoc.getWorld().getName();
            gateData.x         = standLoc.getX();
            gateData.y         = standLoc.getY();
            gateData.z         = standLoc.getZ();
            gateData.yaw       = standLoc.getYaw();
            gateData.pitch     = standLoc.getPitch();

            plugin.getTpGateManager().addGate(gateData);

            player.sendMessage("§a[TP] ゲート「§e" + entryName + " §a→ §e" + exitName + "§a」を設置しました。");
            player.sendMessage("§b[System] §r§7ペアになるゲートを反対側にも設置してください。");
            return true;
        }

        // ==========================================
        // ★ /sac removegate ── 周囲のTPゲートを撤去
        // ==========================================
        if (sub.equalsIgnoreCase("removegate")) {
            if (!player.isOp()) { player.sendMessage("§b[System] §r§cPERMISSION DENIED。"); return true; }
            int count = 0;
            for (org.bukkit.entity.Entity e : player.getNearbyEntities(5, 5, 5)) {
                if (e.getScoreboardTags().contains("sao_tp_gate")) {
                    if (e instanceof org.bukkit.entity.Interaction) {
                        plugin.getTpGateManager().removeGate(e.getUniqueId().toString());
                    }
                    e.remove();
                    count++;
                }
            }
            player.sendMessage(count > 0
                    ? "§e[TP] 周囲の転移ゲートを " + count + " 箇所撤去しました。"
                    : "§c周囲に転移ゲートが見つかりませんでした。");
            return true;
        }

        return true;
    }

    private void showSkillMenu(Player p, PlayerData stats) {
        p.sendMessage(" ");
        p.sendMessage("§b[System] §r§b【ステータス割り当て】 §f所持: §e" + stats.skillPoint + "pt");
        // コマンドは /sac add に統一
        p.spigot().sendMessage(makeBtn("§6[ STR (筋力) ]", "/sac add str", "§f現在の値: " + stats.str + "§f 武器の重さが軽減され、攻撃速度がその分上がります！"));
        p.spigot().sendMessage(makeBtn("§b[ AGI (敏捷) ]", "/sac add agi", "§f現在の値: " + stats.agi + "§f 移動速度が少し上がり、AGI×２の値以下のレベルのモンスターの攻撃をたまに回避します！"));
        p.sendMessage(" ");
    }

    private TextComponent makeBtn(String text, String cmd, String hover) {
        TextComponent msg = new TextComponent(text);
        msg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        msg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(hover).create()));
        return msg;
    }

    // 引数を3つ（送り手、ターゲット名、武器ID）に修正
    /**
     * ★追加: /sac give <ID> <個数> <成功値> <強化値> の実処理。
     * 常にコマンド実行者自身に付与する。成功ボーナス・強化分のステータスは
     * その場で自動割り振りせず、既存の「未割り振りポイント」システム(鍛造台で
     * 自由にDEF/STR等へ振り分けるやつ)にまとめて乗せることで、実際に鍛造台で
     * 生産・強化したのと同じ状態(振り分け前)を再現する。
     */
    private void giveForgedItem(Player player, String id, String amountArg, String successArg, String upgradeArg,
                                SAOWeapon weapon, sac.plugin.info.SACplugin.item.SAOArmor armor) {
        int amount;
        try {
            amount = Math.max(1, Integer.parseInt(amountArg));
        } catch (NumberFormatException e) {
            player.sendMessage("§c[SAO] 個数は数値で指定してください。");
            return;
        }

        int upgradeValue;
        try {
            upgradeValue = Math.max(0, Integer.parseInt(upgradeArg));
        } catch (NumberFormatException e) {
            player.sendMessage("§c[SAO] 強化値は数値で指定してください。");
            return;
        }

        int tierPoints;
        switch (successArg) {
            case "生産成功": tierPoints = 1; break;
            case "大成功": tierPoints = 3; break;
            case "奇跡的成功": tierPoints = 5; break;
            case "無し": tierPoints = 0; break;
            default:
                player.sendMessage("§c[SAO] 成功値は §e生産成功/大成功/奇跡的成功/無し §cのいずれかで指定してください。");
                return;
        }

        ItemStack itemToGive;
        String finalName;
        int totalPendingPoints = tierPoints + upgradeValue;

        if (weapon != null) {
            itemToGive = plugin.getSaoItemManager().createWeaponItem(weapon);
            finalName = weapon.name;

            sac.plugin.info.SACplugin.Smithing.ForgeManager.setUpgradeDisplayCount(itemToGive, upgradeValue);
            // 一旦deltas=0で再計算をかけて、+N表記やLoreを今のカウンタ値に合わせて整える
            sac.plugin.info.SACplugin.Smithing.ForgeManager.applyBonusStatDeltas(itemToGive, weapon, new int[5]);
            // ==========================================
            // ★追加: この時点ではボーナスステータスがまだ全て0(Attack欄に"(+N)"が付いていない)ため、
            // ここで呼べば「移行対象なし」の判定になり、スタンプだけが押される。
            // これが無いと、後で鍛造画面を開いた/攻撃した際に太古の移行処理が誤発火し、
            // 余分な未割り振りポイントが追加されてしまう(通常の鍛造品と同じ不具合)。
            // ==========================================
            sac.plugin.info.SACplugin.Smithing.ForgeManager.migrateLegacyWeaponIfNeeded(itemToGive, weapon);
            sac.plugin.info.SACplugin.Smithing.ForgeManager.setPendingPoints(itemToGive, totalPendingPoints);
            sac.plugin.info.SACplugin.Smithing.ForgeManager.setUpgradeAttempts(itemToGive, upgradeValue);

        } else if (armor != null) {
            itemToGive = plugin.getSaoItemManager().createArmorItem(armor);
            finalName = armor.name;

            sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.setUpgradeDisplayCount(itemToGive, upgradeValue);
            sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.applyBonusStatDeltas(itemToGive, armor, new int[5]);
            // ★追加: 同上の理由で、防具にも生成時点で移行済みスタンプを押す
            sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.migrateArmorSchemeIfNeeded(itemToGive, armor);
            sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.setPendingPoints(itemToGive, totalPendingPoints);
            sac.plugin.info.SACplugin.Smithing.ArmorForgeManager.setUpgradeAttempts(itemToGive, upgradeValue);

        } else {
            player.sendMessage("§c[SAO] ID: " + id + " は武器・防具データに存在しません。");
            return;
        }

        itemToGive.setAmount(amount);

        java.util.HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(itemToGive);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }

        player.sendMessage("§a[SAO] " + finalName + " §aを " + amount + " 個付与しました。 §7(成功値:" + successArg + " / 強化値:+" + upgradeValue + ")");
        if (totalPendingPoints > 0) {
            player.sendMessage("§b[System] §r§d未割り振りポイントが §b" + totalPendingPoints + "回分 §d付与されています。鍛造台の強化メニューから自由に割り振ってください。");
        }
    }

    private void giveAnyItem(Player sender, String targetPlayerName, String id, int amount) {
        Player target = org.bukkit.Bukkit.getPlayer(targetPlayerName);
        if (target == null) {
            sender.sendMessage("§c[SAO] プレイヤー " + targetPlayerName + " はオンラインではありません。");
            return;
        }

        ItemStack itemToGive = null;
        String finalName = id;

        // 1. 武器データ (weapons.json) から検索
        SAOWeapon weapon = plugin.getSaoItemManager().getWeapon(id);
        // ★【追加】2. 防具データから検索
        sac.plugin.info.SACplugin.item.SAOArmor armor = plugin.getSaoItemManager().getArmor(id);
        // 3. 一般アイテム (items.json) から検索
        SAOGeneralItemData genItem = plugin.getSaoItemManager().getGeneralItem(id);
        // 釣具・魚データから検索
        sac.plugin.info.SACplugin.fishing.FishingRodData rod = plugin.getFishingManager().getRod(id);
        sac.plugin.info.SACplugin.fishing.FishData fish = plugin.getFishingManager().getFish(id);

        if (weapon != null) {
            itemToGive = plugin.getSaoItemManager().createWeaponItem(weapon);
            finalName = weapon.name; // JSONのnameを表示名に使用
        } else if (armor != null) {
            // ★【追加】防具だった場合の処理
            itemToGive = plugin.getSaoItemManager().createArmorItem(armor);
            finalName = armor.name;
        }else if (genItem != null) {
            itemToGive = plugin.getSaoItemManager().createGeneralItem(genItem);
            finalName = genItem.name;
        }
        // ==========================================
        // ★追加: 釣りアイテムだった場合の処理
        // ==========================================
        else if (rod != null) {
            itemToGive = plugin.getFishingManager().createRodItem(rod);
            finalName = rod.name;
        } else if (fish != null) {
            itemToGive = plugin.getFishingManager().createFishItem(fish);
            finalName = fish.name;
        }

        if (itemToGive != null) {
            // 個数をセット（1〜64の範囲に制限したい場合は Math.min/max を使うと安全です）
            itemToGive.setAmount(Math.max(1, amount));

            target.getInventory().addItem(itemToGive);
            sender.sendMessage("§a[SAO] " + target.getName() + " に " + finalName + " §aを " + amount + " 個付与しました。");
            target.sendMessage("§a[SAO] 管理者から " + finalName + " §aを " + amount + " 個受け取りました。");
        } else {
            sender.sendMessage("§c[SAO] ID: " + id + " は武器・防具・アイテムデータに存在しません。");
        }
    }

    /**
     * ★追加: "HH:MM" または "HH" 形式の文字列を 0.0〜24.0 の時刻(double)に変換する。
     * パース失敗時は null を返す。
     */
    private Double parseHour(String input) {
        try {
            if (input.contains(":")) {
                String[] parts = input.split(":");
                int h = Integer.parseInt(parts[0].trim());
                int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                if (h < 0 || h > 24 || m < 0 || m >= 60) return null;
                return h + (m / 60.0);
            } else {
                double h = Double.parseDouble(input.trim());
                if (h < 0 || h > 24) return null;
                return h;
            }
        } catch (Exception e) {
            return null;
        }
    }
}