package sac.plugin.info.SACplugin.party;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import sac.plugin.info.SACplugin.Player.PlayerData;
import sac.plugin.info.SACplugin.SACplugin;

import java.util.*;

public class PartyMenuListener implements Listener {
    private final SACplugin plugin;

    // ★追加: Col送金の金額入力待ちプレイヤーを管理するMap (送る人 -> 受け取る人)
    public static final Map<UUID, UUID> colTransferInput = new HashMap<>();

    public PartyMenuListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ① メインメニュー
    public void openPartyMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, "§b《Friend / Party》");

        inv.setItem(2, createButton(Material.BOOK, "§aフレンド欄", "§7フレンド一覧を表示します"));
        inv.setItem(6, createButton(Material.COMPASS, "§bプレイヤー検索", "§7周囲5ブロック以内の", "§7プレイヤーを検索します"));

        if (plugin.getPartyManager().isInParty(player)) {
            inv.setItem(4, createButton(Material.PLAYER_HEAD, "§eパーティーメンバー",
                    "§7─────────────",
                    "§b[Party Bonus]",
                    "§7獲得EXP §a×1.2 §7(メンバー全員同量を共有)",
                    "§7全身防具装備者の防御力 §a×0.5 §7がパーティー全体に付与",
                    "§7─────────────",
                    "§7現在のパーティーメンバーを確認・操作します"));
        }
        fillBackground(inv);
        player.openInventory(inv);
    }

    // ② プレイヤー検索メニュー
    public void openNearbyPlayersMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b《Nearby Players》");
        for (Entity entity : player.getNearbyEntities(5.0, 5.0, 5.0)) {
            if (entity instanceof Player) {
                Player target = (Player) entity;
                PlayerData data = plugin.getPlayerManager().getStats(target);
                int level = (data != null) ? data.level : 1;
                boolean inParty = plugin.getPartyManager().isInParty(target);

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(target);
                meta.setDisplayName("§e" + target.getName());
                meta.setLore(Arrays.asList(
                        "§7所属ギルド: §c未実装",
                        "§7パーティー: " + (inParty ? "§a参加中" : "§c未参加"),
                        "",
                        "§bクリックしてアクションメニューを開く"
                ));
                head.setItemMeta(meta);
                if (inv.firstEmpty() != -1) inv.addItem(head);
            }
        }
        player.openInventory(inv);
    }

    // ③ アクションメニュー (プレイヤー検索から開く用)
    public void openPlayerActionMenu(Player player, Player target, String sourceTitle) {
        Inventory inv = Bukkit.createInventory(null, 9, "§b《Action》 §8" + target.getName());
        boolean inParty = plugin.getPartyManager().isInParty(target);

        ItemStack partyBtn = createButton(Material.BEACON, "§aパーティー招待",
                inParty ? "§c既にパーティーに参加しています" : "§eクリックして招待を送信");
        inv.setItem(2, partyBtn);

        // フレンド申請ボタン
        PlayerData pData = plugin.getPlayerManager().getStats(player);
        boolean isFriend = pData != null && pData.friends.contains(target.getUniqueId());
        ItemStack friendBtn = createButton(Material.PAPER, "§bフレンド申請",
                isFriend ? "§c既にフレンドです" : "§eクリックして申請を送信");
        inv.setItem(4, friendBtn);

        inv.setItem(6, createButton(Material.SHIELD, "§6ギルド招待 (未実装)"));
        // ★追加: 相手の実績を確認するボタン
        inv.setItem(0, createButton(Material.ENCHANTED_BOOK, "§6実績確認", "§7相手の獲得した実績を確認します"));
        fillBackground(inv);

        // ★追加: 周囲のプレイヤー検索から開いた時のみ、右端(スロット8)にCol取引ボタンを配置
        if ("§b《Nearby Players》".equals(sourceTitle)) {
            inv.setItem(8, createButton(Material.GOLD_INGOT, "§eCol取引", "§7クリックしてColを送ります"));
        }

        player.openInventory(inv);
    }

    // ④ パーティーメンバーメニュー
    public void openPartyMembersMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, "§b《Party Members》");
        List<UUID> members = plugin.getPartyManager().getPartyMembers(player);
        int slot = 0;

        for (UUID memberId : members) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(memberId);
            if (slot < 7) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(p);
                meta.setDisplayName("§e" + p.getName());
                head.setItemMeta(meta);
                inv.setItem(slot++, head);
            }
        }

        boolean chatEnabled = plugin.getPartyManager().isPartyChatEnabled(player);
        inv.setItem(7, createButton(Material.PAPER, "§bパーティーチャット切替",
                chatEnabled ? "§aオン (メンバー専用)" : "§cオフ (サーバー全体)"));
        inv.setItem(8, createButton(Material.BARRIER, "§cパーティー脱退", "§7現在のパーティーから抜けます"));
        fillBackground(inv);
        player.openInventory(inv);
    }

    // ===============================================
    // ★ 追加: フレンドリスト表示メニュー
    // ===============================================
    public void openFriendListMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§b《Friend List》");
        PlayerData data = plugin.getPlayerManager().getStats(player);

        if (data != null) {
            for (UUID friendId : data.friends) {
                OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(friendId);
                boolean isOnline = offlineTarget.isOnline();

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(offlineTarget);
                meta.setDisplayName("§e" + offlineTarget.getName());
                meta.setLore(Arrays.asList(
                        "§7ステータス: " + (isOnline ? "§aオンライン" : "§cオフライン"),
                        "",
                        "§bクリックしてアクションメニューを開く"
                ));
                head.setItemMeta(meta);
                if (inv.firstEmpty() != -1) inv.addItem(head);
            }
        }
        player.openInventory(inv);
    }

    // ===============================================
    // ★ 追加: フレンド専用アクションメニュー
    // ===============================================
    public void openFriendActionMenu(Player player, String targetName) {
        Inventory inv = Bukkit.createInventory(null, 9, "§b《Friend Action》 §8" + targetName);
        Player target = Bukkit.getPlayer(targetName);
        boolean isOnline = (target != null && target.isOnline());

        if (isOnline) {
            inv.setItem(2, createButton(Material.BEACON, "§aパーティー招待", "§eクリックして招待を送信"));
            inv.setItem(4, createButton(Material.WRITABLE_BOOK, "§b個人メッセージを送る", "§7クリック後、チャット欄に", "§7メッセージを入力してください"));
        } else {
            inv.setItem(2, createButton(Material.BEACON, "§8パーティー招待", "§cプレイヤーがオフラインです"));
            inv.setItem(4, createButton(Material.WRITABLE_BOOK, "§8個人メッセージを送る", "§cプレイヤーがオフラインです"));
        }

        inv.setItem(6, createButton(Material.BARRIER, "§cフレンド削除", "§7クリックでフレンドリストから削除します"));
        // ★追加: フレンドの実績を確認するボタン(オフラインでも保存データから閲覧できる)
        inv.setItem(0, createButton(Material.ENCHANTED_BOOK, "§6実績確認", "§7フレンドの獲得した実績を確認します"));
        fillBackground(inv);
        player.openInventory(inv);
    }

    // クリックイベントの処理
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.startsWith("§b《Friend / Party》") && !title.startsWith("§b《Nearby Players》") &&
                !title.startsWith("§b《Action》") && !title.equals("§b《Party Members》") &&
                !title.equals("§b《Friend List》") && !title.startsWith("§b《Friend Action》")) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return;

        String itemName = item.getItemMeta().getDisplayName();

        // ① メインメニュー
        if (title.equals("§b《Friend / Party》")) {
            if (itemName.equals("§bプレイヤー検索")) openNearbyPlayersMenu(player);
            else if (itemName.equals("§aフレンド欄")) openFriendListMenu(player);
            else if (itemName.equals("§eパーティーメンバー")) openPartyMembersMenu(player);
        }
        // ② 検索結果メニュー
        else if (title.equals("§b《Nearby Players》") || title.equals("§b《Party Members》")) {
            if (item.getType() == Material.PLAYER_HEAD) {
                String targetName = itemName.replace("§e", "");
                Player target = Bukkit.getPlayer(targetName);

                // ★【追加】自分自身の顔をクリックした場合はエラー音を鳴らして弾く
                if (target != null && target.getUniqueId().equals(player.getUniqueId())) {
                    player.sendMessage("§b[System] §r§c自分自身にアクションはできません！");
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }

                if (target != null && target.isOnline()) {
                    // ★変更: 第3引数に title を渡すようにする
                    openPlayerActionMenu(player, target, title);
                } else {
                    player.sendMessage("§b[System] §r§cそのプレイヤーはオフラインです。");
                    player.closeInventory();
                }
            }
            // ==========================================
            // ★【ここから追加】チャット切替と脱退ボタンの処理
            // ==========================================
            else if (title.equals("§b《Party Members》")) {
                if (itemName.equals("§bパーティーチャット切替")) {
                    boolean isNowOn = plugin.getPartyManager().togglePartyChat(player);
                    player.sendMessage(isNowOn ? "§b[System] §r§aパーティーチャットをオンにしました。(メンバー専用)" : "§cパーティーチャットをオフにしました。(サーバー全体)");
                    player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 1.5f);
                    openPartyMembersMenu(player); // メニューを再描画してボタンの文字を切り替える
                } else if (itemName.equals("§cパーティー脱退")) {
                    plugin.getPartyManager().leaveParty(player);
                    player.closeInventory();
                }
            }
        }
        // ③ アクションメニュー (通常)
        else if (title.startsWith("§b《Action》")) {
            String targetName = title.replace("§b《Action》 §8", "");
            Player target = Bukkit.getPlayer(targetName);
            if (target == null || !target.isOnline()) {
                player.sendMessage("§b[System] §r§cプレイヤーがオフラインです。");
                player.closeInventory();
                return;
            }

            if (itemName.equals("§6実績確認")) {
                player.closeInventory();
                plugin.getAchievementMenu().openOther(player, target.getUniqueId(), target.getName());
            } else if (itemName.equals("§aパーティー招待")) {
                player.closeInventory();
                plugin.getPartyManager().sendInvite(player, target);
            } else if (itemName.equals("§bフレンド申請")) {
                player.closeInventory();
                plugin.getFriendManager().sendRequest(player, target);
            } else if (itemName.equals("§eCol取引")) {
                player.closeInventory();
                sendColTransferConfirm(player, targetName);
            }else if (itemName.contains("未実装")) {
                player.sendMessage("§b[System] §r§c未実装です！");
            }
        }
        // ④ フレンドリストメニュー
        else if (title.equals("§b《Friend List》")) {
            if (item.getType() == Material.PLAYER_HEAD) {
                String targetName = itemName.replace("§e", "");
                openFriendActionMenu(player, targetName);
            }
        }
        // ⑤ フレンド専用アクションメニュー
        else if (title.startsWith("§b《Friend Action》")) {
            String targetName = title.replace("§b《Friend Action》 §8", "");

            if (itemName.equals("§6実績確認")) {
                player.closeInventory();
                OfflinePlayer achTarget = Bukkit.getOfflinePlayer(targetName);
                plugin.getAchievementMenu().openOther(player, achTarget.getUniqueId(), targetName);
            } else if (itemName.equals("§aパーティー招待")) {
                Player target = Bukkit.getPlayer(targetName);
                if (target != null) {
                    player.closeInventory();
                    plugin.getPartyManager().sendInvite(player, target);
                }
            } else if (itemName.equals("§cフレンド削除")) {
                player.closeInventory();
                // UUIDを取得して削除処理
                OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
                plugin.getFriendManager().removeFriend(player, offlineTarget.getUniqueId(), targetName);
            } else if (itemName.equals("§b個人メッセージを送る")) {
                player.closeInventory();
                player.sendMessage("§e[System] " + targetName + " へのメッセージをチャット欄に入力してください。(※現在は未実装のため送信されません)");
                // ※ここにDM送信の入力待機処理を追加できます
            } else if (itemName.contains("§8")) {
                player.sendMessage("§b[System] §r§c相手がオフラインのため実行できません。");
            }
        }
    }

    private ItemStack createButton(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private void fillBackground(Inventory inv) {
        ItemStack bg = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = bg.getItemMeta();
        meta.setDisplayName("§f");
        meta.setCustomModelData(9000);
        bg.setItemMeta(meta);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, bg);
        }
    }
    // ==========================================
    // ★ Col取引システム
    // ==========================================

    // 1. チャット欄に「はい/いいえ」のテキストを送信する
    private void sendColTransferConfirm(Player player, String targetName) {
        player.sendMessage("§7-----------------------------------");
        player.sendMessage("§e[System] §f" + targetName + " にColを送りますか？");

        // 裏技: 存在しない架空のコマンドを仕込み、あとで横取りする
        TextComponent yes = new TextComponent("  §a[ はい ]  ");
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sao_col_yes " + targetName));

        TextComponent no = new TextComponent("  §c[ いいえ ]  ");
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sao_col_no"));

        yes.addExtra(no);
        player.spigot().sendMessage(yes);
        player.sendMessage("§7-----------------------------------");
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
    }

    // 2. 「はい/いいえ」を押した時の架空コマンドを横取りして処理する
    @EventHandler
    public void onColCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        Player player = event.getPlayer();

        if (message.startsWith("/sao_col_yes ")) {
            event.setCancelled(true); // コマンド不明エラーを出さないようにキャンセル
            String targetName = message.replace("/sao_col_yes ", "");
            Player target = Bukkit.getPlayer(targetName);

            if (target == null || !target.isOnline()) {
                player.sendMessage("§b[System] §r§c相手がオフラインになりました。");
                return;
            }

            // 金額入力待機リストに登録
            colTransferInput.put(player.getUniqueId(), target.getUniqueId());
            player.sendMessage("§e[System] §f送りたいColの金額をチャットに入力してください！");
            player.sendMessage("§b[System] §r§7(キャンセルする場合は 'cancel' と入力してください)");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);

        } else if (message.equals("/sao_col_no")) {
            event.setCancelled(true);
            player.sendMessage("§b[System] §r§cColの取引をキャンセルしました。");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    // 3. 金額のチャット入力を検知して取引を実行する
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onColChatInput(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        // 待機リストに入っていれば処理開始
        if (colTransferInput.containsKey(player.getUniqueId())) {
            event.setCancelled(true); // 通常のチャットには流さない

            String message = event.getMessage();
            if (message.equalsIgnoreCase("cancel")) {
                colTransferInput.remove(player.getUniqueId());
                player.sendMessage("§b[System] §r§c送金をキャンセルしました。");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            UUID targetId = colTransferInput.get(player.getUniqueId());

            try {
                long amount = Long.parseLong(message);
                if (amount <= 0) {
                    player.sendMessage("§b[System] §r§c1以上の金額を入力してください！(キャンセル: 'cancel')");
                    return;
                }
                // 非同期イベントからメインスレッドに戻して安全にデータを操作する
                Bukkit.getScheduler().runTask(plugin, () -> processColTransfer(player, targetId, amount));

            } catch (NumberFormatException e) {
                player.sendMessage("§b[System] §r§c半角数字で入力してください！(キャンセル: 'cancel')");
            }
        }
    }

    // 4. 実際のCol移動・保存・スコアボード更新
    private void processColTransfer(Player sender, UUID targetId, long amount) {
        colTransferInput.remove(sender.getUniqueId()); // 待機解除

        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§b[System] §r§c相手がオフラインになりました。");
            return;
        }

        PlayerData senderData = plugin.getPlayerManager().getStats(sender);
        PlayerData targetData = plugin.getPlayerManager().getStats(target);
        if (senderData == null || targetData == null) return;

        if (senderData.col < amount) {
            sender.sendMessage("§b[System] §r§cColが不足しています！(所持: " + senderData.col + " Col)");
            sender.playSound(sender.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        // Colの移動
        senderData.col -= amount;
        targetData.col += amount;

        // PlayerDataの保存
        plugin.getPlayerManager().savePlayerData(sender, senderData);
        plugin.getPlayerManager().savePlayerData(target, targetData);

        // スコアボードの更新
        if (plugin.getSaoScoreboard() != null) {
            plugin.getSaoScoreboard().updateScoreboard(sender, senderData);
            plugin.getSaoScoreboard().updateScoreboard(target, targetData);
        }

        // 成功通知とSE
        sender.sendMessage("§a[System] " + target.getName() + " に " + amount + " Colを送りました！");
        target.sendMessage("§a[System] " + sender.getName() + " から " + amount + " Colを受け取りました！");

        sender.playSound(sender.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        target.playSound(target.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
    }
    // ==========================================
    // ★ 追加: プレイヤー死亡時・ログアウト時の自動パーティー脱退
    // ==========================================

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        // 死亡したプレイヤーがパーティーに所属していたら、強制的に脱退させる
        if (plugin.getPartyManager().isInParty(player)) {
            plugin.getPartyManager().leaveParty(player);
            // ※leaveParty() の中で、他のメンバーには「〇〇が脱退しました」と自動で通知されます
        }
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // サーバーから抜けた場合も、パーティーから強制的に脱退させる
        if (plugin.getPartyManager().isInParty(player)) {
            plugin.getPartyManager().leaveParty(player);
        }

        // ついでに、Col送金中にログアウトした場合のバグ防止（入力待ちリストからの削除）
        colTransferInput.remove(player.getUniqueId());
    }
}
