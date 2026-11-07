package sac.plugin.info.SACplugin.Event;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import sac.plugin.info.SACplugin.SACplugin;
import sac.plugin.info.SACplugin.farming.SAOFarmManager;

public class SAOFarmingListener implements Listener {
    private final SACplugin plugin;

    public SAOFarmingListener(SACplugin plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // ★ バニラ農業の完全無効化
    // ==========================================
    @EventHandler
    public void onMoistureChange(MoistureChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onFarmlandFade(BlockFadeEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND) event.setCancelled(true);
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && event.getTo() == Material.DIRT) {
            event.setCancelled(true);
        }
    }

    // ==========================================
    // ★ 1. Farm Point のクリック (メニューを開く)
    // ==========================================
    @EventHandler
    public void onInteractEntity(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand
                && event.getRightClicked().getScoreboardTags().contains("sao_farm_point")) {
            event.setCancelled(true);
            if (plugin.getFarmMenuListener() != null) {
                // ★ UUID ではなく Entity を渡す（座標ベースの farmId を生成するため）
                plugin.getFarmMenuListener().openFarmMenu(event.getPlayer(), event.getRightClicked());
                event.getPlayer().playSound(event.getPlayer().getLocation(), "sao.menu.click", 1.0f, 1.0f);
            }
        }
    }

    // ==========================================
    // ★ ヘルパー: アイテムが農業ツール(種・ジョウロ・肥料)かどうかを判定
    // ==========================================
    private boolean isFarmTool(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return false;
        String itemId = ChatColor.stripColor(item.getItemMeta().getLore().get(0));
        return plugin.getFarmManager().getCrop(itemId) != null
                || itemId.equals("watering_can")
                || itemId.equals("fertilizer");
    }

    // ==========================================
    // ★ 2. 種まき・水やり・肥料・収穫（右クリック統合）
    // ==========================================
    @EventHandler
    public void onFarmAction(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // PlayerInteractEvent はメインハンド・オフハンドで2回発火するため、
        // メインハンドの発火のみ処理する（オフハンド側は無視）
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        org.bukkit.block.Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();
        // 常にメインハンドのアイテムを参照する
        ItemStack item = player.getInventory().getItemInMainHand();

        // ==========================================
        // --- D. 収穫（素手 or 農業アイテム以外での右クリック）---
        // アドベンチャーモード対応のため右クリックで収穫します。
        // ジョウロ・種・肥料を持っているときは収穫をスキップし、
        // 以降のツール処理（B: 水やり / C: 肥料）へ流します。
        // ==========================================
        if (block.getType() == Material.WHEAT || block.getType() == Material.DEAD_BUSH) {
            if (!isFarmTool(item)) {
                // 農業ツールを持っていない → 収穫処理
                event.setCancelled(true);

                org.bukkit.block.Block farmland = block.getRelative(org.bukkit.block.BlockFace.DOWN);
                if (farmland.getType() != Material.FARMLAND) return;

                String farmId = plugin.getFarmManager().getFarmIdAt(farmland.getLocation());
                if (farmId == null) return;
                SAOFarmManager.FarmData farm = plugin.getFarmManager().getFarm(farmId);
                if (farm == null) return;

                if (!farm.ownerUuid.equals(player.getUniqueId().toString())) {
                    player.sendMessage("§b[System] §r§c他人の農地を荒らすことはできません！");
                    return;
                }

                String coordKey = farmland.getX() + "," + farmland.getY() + "," + farmland.getZ();
                SAOFarmManager.PlantedCrop pCrop = farm.crops.get(coordKey);
                if (pCrop == null) return;

                // setType 前にブロック種別を保存
                boolean wasDeadBush = (block.getType() == Material.DEAD_BUSH);

                // ブロックを除去してデータをクリア
                block.setType(Material.AIR);
                farm.crops.remove(coordKey);
                plugin.getFarmManager().saveFarms();

                if (!wasDeadBush && pCrop.stage >= 4) {
                    SAOFarmManager.CropData cData = plugin.getFarmManager().getCrop(pCrop.cropId);
                    if (cData != null) {
                        String resultId = pCrop.isFertilized ? cData.rareResultItemId : cData.resultItemId;
                        sac.plugin.info.SACplugin.item.SAOGeneralItemData gData = plugin.getSaoItemManager().getGeneralItem(resultId);
                        if (gData != null) {
                            ItemStack drop = plugin.getSaoItemManager().createGeneralItem(gData);
                            block.getWorld().dropItemNaturally(block.getLocation(), drop);
                            // ★追加(実績): 肥料で育てた最高品質の収穫を記録する
                            if (pCrop.isFertilized && plugin.getAchievementManager() != null) {
                                plugin.getAchievementManager().onBestCrop(player, pCrop.cropId);
                            }
                            player.sendMessage("§a[System] " + gData.name + " §aを収穫した！");
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
                        }
                    }
                } else if (wasDeadBush) {
                    player.sendMessage("§b[System] §r§7枯れ木を引っこ抜いた...");
                } else {
                    player.sendMessage("§b[System] §r§7成長途中の作物を引っこ抜いた。");
                }
                return;
            }
            // isFarmTool == true の場合はここを素通りして以降のツール処理へ進む
        }

        // 手持ちアイテムがない場合、以下の種まき・水やり・肥料処理はスキップ
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return;
        String itemId = ChatColor.stripColor(item.getItemMeta().getLore().get(0));

        // --- A. 種まき ---
        SAOFarmManager.CropData cropData = plugin.getFarmManager().getCrop(itemId);
        if (cropData != null) {
            event.setCancelled(true);
            if (block.getType() != Material.FARMLAND) return;

            String farmId = plugin.getFarmManager().getFarmIdAt(block.getLocation());
            if (farmId == null) {
                player.sendMessage("§b[System] §r§cFarm Pointの範囲内(5x5)にのみ植えることができます！");
                return;
            }
            SAOFarmManager.FarmData farm = plugin.getFarmManager().getFarm(farmId);
            if (farm == null || !farm.ownerUuid.equals(player.getUniqueId().toString())) {
                player.sendMessage("§b[System] §r§cここはあなたの農地ではありません！");
                return;
            }
            String coordKey = block.getX() + "," + block.getY() + "," + block.getZ();
            if (farm.crops.containsKey(coordKey)) {
                player.sendMessage("§b[System] §r§cここには既に種が植えられています！");
                return;
            }

            item.setAmount(item.getAmount() - 1);
            SAOFarmManager.PlantedCrop pCrop = new SAOFarmManager.PlantedCrop();
            pCrop.cropId = itemId;
            farm.crops.put(coordKey, pCrop);
            plugin.getFarmManager().saveFarms();

            org.bukkit.block.Block above = block.getRelative(org.bukkit.block.BlockFace.UP);
            above.setType(Material.WHEAT);
            org.bukkit.block.data.Ageable ageable = (org.bukkit.block.data.Ageable) above.getBlockData();
            ageable.setAge(0);
            above.setBlockData(ageable);
            player.playSound(player.getLocation(), Sound.ITEM_CROP_PLANT, 1f, 1f);
            return;
        }

        // --- B. 水やり (ジョウロ) ---
        if (itemId.equals("watering_can")) {
            event.setCancelled(true);

            sac.plugin.info.SACplugin.Player.PlayerData stats = plugin.getPlayerManager().getStats(player);
            boolean hasSkill = false;
            if (stats != null) {
                for (String s : stats.equippedSkills) {
                    if ("農業".equals(s)) {
                        hasSkill = true;
                        break;
                    }
                }
            }
            if (!hasSkill) {
                player.sendMessage("§b[System] §r§cジョウロを使うにはスキルスロットに「農業」をセットしてください！");
                return;
            }

            // クリックしたのが小麦でも農地でも、農地ブロックを取得する
            org.bukkit.block.Block farmland;
            if (block.getType() == Material.FARMLAND) {
                farmland = block;
            } else if (block.getType() == Material.WHEAT) {
                farmland = block.getRelative(org.bukkit.block.BlockFace.DOWN);
            } else {
                return;
            }
            if (farmland.getType() != Material.FARMLAND) return;

            String farmId = plugin.getFarmManager().getFarmIdAt(farmland.getLocation());
            if (farmId == null) return;
            SAOFarmManager.FarmData farm = plugin.getFarmManager().getFarm(farmId);
            if (farm == null || !farm.ownerUuid.equals(player.getUniqueId().toString())) {
                player.sendMessage("§b[System] §r§c自分の農地にのみ水やりができます！");
                return;
            }
            String coordKey = farmland.getX() + "," + farmland.getY() + "," + farmland.getZ();
            SAOFarmManager.PlantedCrop pCrop = farm.crops.get(coordKey);
            if (pCrop == null) {
                player.sendMessage("§b[System] §r§c種が植えられていません！");
                return;
            }
            if (pCrop.isWatered) {
                player.sendMessage("§b[System] §r§cすでに土は湿っています！");
                return;
            }

            pCrop.isWatered = true;
            plugin.getFarmManager().saveFarms();
            org.bukkit.block.data.type.Farmland fData = (org.bukkit.block.data.type.Farmland) farmland.getBlockData();
            fData.setMoisture(7);
            farmland.setBlockData(fData);

            player.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, farmland.getLocation().add(0.5, 1, 0.5), 20, 0.3, 0.3, 0.3, 0.1);
            player.playSound(player.getLocation(), Sound.BLOCK_WATER_AMBIENT, 1f, 1f);
            return;
        }

        // --- C. 肥料 ---
        if (itemId.equals("fertilizer")) {
            event.setCancelled(true);
            if (block.getType() != Material.WHEAT) return;
            org.bukkit.block.Block farmland = block.getRelative(org.bukkit.block.BlockFace.DOWN);

            String farmId = plugin.getFarmManager().getFarmIdAt(farmland.getLocation());
            if (farmId == null) return;
            SAOFarmManager.FarmData farm = plugin.getFarmManager().getFarm(farmId);
            if (farm == null || !farm.ownerUuid.equals(player.getUniqueId().toString())) return;

            String coordKey = farmland.getX() + "," + farmland.getY() + "," + farmland.getZ();
            SAOFarmManager.PlantedCrop pCrop = farm.crops.get(coordKey);
            if (pCrop == null || pCrop.isFertilized) return;

            SAOFarmManager.CropData cData = plugin.getFarmManager().getCrop(pCrop.cropId);
            if (cData != null) {
                item.setAmount(item.getAmount() - 1);
                player.playSound(player.getLocation(), Sound.ITEM_BONE_MEAL_USE, 1f, 1f);
                if (pCrop.stage == cData.fertilizerStage) {
                    pCrop.isFertilized = true;
                    plugin.getFarmManager().saveFarms();
                    player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3, 0.1);
                } else {
                    player.getWorld().spawnParticle(org.bukkit.Particle.ASH, block.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3, 0.1);
                }
            }
        }
    }

    // ==========================================
    // ★ 3. サバイバルモードでのブロック破壊による収穫を無効化
    // 収穫は右クリック(onFarmAction)で行うため、誤破壊・バニラドロップを防ぎます
    // ==========================================
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        org.bukkit.block.Block block = event.getBlock();
        if (block.getType() != Material.WHEAT && block.getType() != Material.DEAD_BUSH) return;

        org.bukkit.block.Block farmland = block.getRelative(org.bukkit.block.BlockFace.DOWN);
        if (farmland.getType() != Material.FARMLAND) return;

        String farmId = plugin.getFarmManager().getFarmIdAt(farmland.getLocation());
        if (farmId != null) {
            event.setCancelled(true);
        }
    }

    // ==========================================
    // ★ 小麦の「自然成長」を完全に禁止する
    // ==========================================
    @EventHandler
    public void onBlockGrow(org.bukkit.event.block.BlockGrowEvent event) {
        if (event.getBlock().getType() == Material.WHEAT || event.getNewState().getType() == Material.WHEAT) {
            event.setCancelled(true);
        }
    }
}