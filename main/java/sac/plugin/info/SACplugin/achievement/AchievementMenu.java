package sac.plugin.info.SACplugin.achievement;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sac.plugin.info.SACplugin.SACplugin;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 実績確認メニュー(自分用 / 他プレイヤー閲覧用)。
 * - ノーマル: 常に表示(獲得方法・進捗つき)
 * - エクストラ / ユニーク: 獲得するまで一覧に存在しない
 * - 自分のメニューで獲得済みの実績をクリック → 称号に設定。装備中の実績をクリック → 取り外し
 */
public class AchievementMenu implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45, SLOT_FILTER = 47, SLOT_BACK = 49, SLOT_UNEQUIP = 51, SLOT_NEXT = 53;

    private enum Filter {
        ALL("すべて", null), NORMAL("ノーマル", AchievementType.NORMAL),
        EXTRA("エクストラ", AchievementType.EXTRA), UNIQUE("ユニーク", AchievementType.UNIQUE);
        final String label; final AchievementType type;
        Filter(String label, AchievementType type) { this.label = label; this.type = type; }
        Filter next() { return values()[(ordinal() + 1) % values().length]; }
    }

    /** このメニューの状態を持つホルダー(タイトル文字列に頼らずに判定できる) */
    private static class AchHolder implements InventoryHolder {
        final UUID target; final String targetName; final boolean own;
        int page; Filter filter;
        final Map<Integer, String> slotToId = new HashMap<>();
        Inventory inv;
        AchHolder(UUID target, String targetName, boolean own, int page, Filter filter) {
            this.target = target; this.targetName = targetName; this.own = own; this.page = page; this.filter = filter;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    private final SACplugin plugin;
    private final AchievementManager manager;

    public AchievementMenu(SACplugin plugin, AchievementManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** 自分の実績メニューを開く */
    public void openOwn(Player player) {
        open(player, player.getUniqueId(), player.getName(), true, 0, Filter.ALL);
    }

    /** 他プレイヤー(オンライン/オフライン問わず)の実績を閲覧する */
    public void openOther(Player viewer, UUID target, String targetName) {
        boolean own = viewer.getUniqueId().equals(target);
        open(viewer, target, targetName, own, 0, Filter.ALL);
    }

    private void open(Player viewer, UUID target, String targetName, boolean own, int page, Filter filter) {
        AchHolder holder = new AchHolder(target, targetName, own, page, filter);
        String title = own ? "§b《 実績 》" : "§b《 実績 》 §8" + targetName;
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inv = inv;
        render(viewer, holder);
        viewer.openInventory(inv);
    }

    private void render(Player viewer, AchHolder h) {
        Inventory inv = h.inv;
        inv.clear();
        h.slotToId.clear();

        AchData ad = manager.getDataForView(h.target);
        List<AchievementDef> shown = new ArrayList<>();
        for (AchievementDef def : manager.getDefs()) {
            if (h.filter.type != null && def.type != h.filter.type) continue;
            boolean owned = manager.isOwned(ad, def);
            if (def.type != AchievementType.NORMAL && !owned) continue;   // エクストラ/ユニークは獲得するまで存在を見せない
            shown.add(def);
        }
        // ノーマル -> エクストラ -> ユニークの順(各々は定義順のまま)
        shown.sort(Comparator.comparingInt(d -> d.type.ordinal()));

        int pages = Math.max(1, (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (h.page >= pages) h.page = pages - 1;
        if (h.page < 0) h.page = 0;

        int start = h.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < shown.size(); i++) {
            AchievementDef def = shown.get(start + i);
            inv.setItem(i, buildItem(viewer, h, ad, def));
            h.slotToId.put(i, def.id);
        }

        // ---- 下段のボタン ----
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inv.setItem(s, glass);
        if (h.page > 0) inv.setItem(SLOT_PREV, named(Material.ARROW, "§e前のページ", "§7" + (h.page + 1) + " / " + pages));
        if (h.page < pages - 1) inv.setItem(SLOT_NEXT, named(Material.ARROW, "§e次のページ", "§7" + (h.page + 1) + " / " + pages));
        inv.setItem(SLOT_FILTER, named(Material.HOPPER, "§b表示する種別: §f" + h.filter.label,
                "§7クリックで切り替え (すべて → ノーマル → エクストラ → ユニーク)"));
        inv.setItem(SLOT_BACK, named(Material.BARRIER, h.own ? "§c戻る" : "§c閉じる"));

        int owned = 0, total = 0;
        for (AchievementDef def : manager.getDefs()) {
            if (def.type == AchievementType.NORMAL) total++;
            if (def.type == AchievementType.NORMAL && manager.isOwned(ad, def)) owned++;
        }
        List<String> info = new ArrayList<>();
        info.add("§7ノーマル達成: §f" + owned + " / " + total);
        AchievementDef eq = ad.equipped == null ? null : manager.getDef(ad.equipped);
        if (eq != null && manager.isOwned(ad, eq)) info.add("§7称号: " + eq.type.color + eq.name);
        else info.add("§7称号: §8なし");
        if (h.own) {
            info.add("§7実績をクリックすると名前の横に表示(1つだけ)");
            info.add("§7装備中の実績をもう一度クリックで取り外し");
            inv.setItem(SLOT_UNEQUIP, named(Material.NAME_TAG, "§e称号を外す", "§7クリックで装備中の称号を取り外します"));
        }
        inv.setItem(48, named(Material.BOOK, "§6" + (h.own ? "あなた" : h.targetName) + "の実績", info.toArray(new String[0])));
    }

    private ItemStack buildItem(Player viewer, AchHolder h, AchData ad, AchievementDef def) {
        boolean owned = manager.isOwned(ad, def);
        boolean equipped = owned && def.id.equals(ad.equipped);

        Material mat;
        if (!owned) mat = Material.GRAY_DYE;
        else if (def.type == AchievementType.NORMAL) mat = Material.EMERALD;
        else if (def.type == AchievementType.EXTRA) mat = Material.DIAMOND;
        else mat = Material.NETHER_STAR;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((owned ? def.type.color : "§7") + def.name + (equipped ? " §a[装備中]" : ""));

        List<String> lore = new ArrayList<>();
        lore.add("§8種別: " + def.type.color + def.type.label);
        if (def.type == AchievementType.NORMAL || owned) lore.add("§7" + def.description);

        if (owned) {
            long at = manager.grantedAt(ad, def);
            if (at > 0) lore.add("§8獲得日: " + new SimpleDateFormat("yyyy/MM/dd HH:mm").format(new Date(at)));
        } else if (h.own) {
            Player self = viewer;
            int[] pr = manager.progressOf(self, def);
            if (pr != null) lore.add("§7進捗: §f" + pr[0] + " / " + pr[1] + " " + bar(pr[0], pr[1]));
        }
        if (def.isUnique()) {
            AchievementManager.Holder holder = manager.getHolder(def.id);
            if (holder != null) lore.add("§6現在の保持者: §e" + holder.name);
        }
        if (h.own && owned) {
            lore.add("");
            lore.add(equipped ? "§cクリックで称号を取り外す" : "§eクリックで称号に設定する");
        }
        meta.setLore(lore);
        if (owned) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private static String bar(int cur, int max) {
        int n = 10;
        int filled = max <= 0 ? 0 : (int) Math.round((double) Math.min(cur, max) / max * n);
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < n; i++) {
            if (i == filled) sb.append("§8");
            sb.append("|");
        }
        return sb.toString();
    }

    private static ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    // ==========================================
    // クリック処理
    // ==========================================
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof AchHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        Player p = (Player) e.getWhoClicked();
        AchHolder h = (AchHolder) e.getView().getTopInventory().getHolder();
        int slot = e.getRawSlot();

        if (slot == SLOT_PREV && h.page > 0) { h.page--; render(p, h); return; }
        if (slot == SLOT_NEXT) { h.page++; render(p, h); return; }
        if (slot == SLOT_FILTER) { h.filter = h.filter.next(); h.page = 0; render(p, h); return; }
        if (slot == SLOT_BACK) {
            if (h.own) {
                p.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sac.plugin.info.SACplugin.menu.MenuListener ml = plugin.getMenuListener();
                    if (ml != null) ml.openStatsChoiceMenu(p);
                });
            } else {
                p.closeInventory();
            }
            return;
        }
        if (slot == SLOT_UNEQUIP && h.own) {
            manager.unequip(p);
            p.sendMessage("§6[実績] §f称号を取り外しました。");
            render(p, h);
            return;
        }

        String id = h.slotToId.get(slot);
        if (id == null || !h.own) return;   // 他人のメニューは閲覧のみ

        AchData ad = manager.getDataForView(p.getUniqueId());
        AchievementDef def = manager.getDef(id);
        if (def == null || !manager.isOwned(ad, def)) return;

        if (id.equals(ad.equipped)) {
            manager.unequip(p);
            p.sendMessage("§6[実績] §f称号『" + def.name + "』を取り外しました。");
        } else if (manager.equip(p, id)) {
            p.sendMessage("§6[実績] §f称号を『" + def.type.color + def.name + "§f』に設定しました。");
        }
        render(p, h);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof AchHolder) e.setCancelled(true);
    }
}
