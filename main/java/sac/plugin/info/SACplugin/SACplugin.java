package sac.plugin.info.SACplugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import sac.plugin.info.SACplugin.BossAI.BossAIRegistry;
import sac.plugin.info.SACplugin.Event.*;
import sac.plugin.info.SACplugin.Player.PlayerManager;
import sac.plugin.info.SACplugin.Player.DeathBoardManager;
import sac.plugin.info.SACplugin.Smithing.*;
import sac.plugin.info.SACplugin.Tutorial.TutorialCommand;
import sac.plugin.info.SACplugin.Tutorial.TutorialListener;
import sac.plugin.info.SACplugin.Tutorial.TutorialStepListener;
import sac.plugin.info.SACplugin.Tutorial.TutorialFlowListener;
import sac.plugin.info.SACplugin.Tutorial.TutorialJsonManager;
import sac.plugin.info.SACplugin.accessory.AccessoryForgeListener;
import sac.plugin.info.SACplugin.accessory.AccessoryManager;
import sac.plugin.info.SACplugin.accessory.AppraisalListener;
import sac.plugin.info.SACplugin.boss.*;
import sac.plugin.info.SACplugin.command.SACReloadCommand;
import sac.plugin.info.SACplugin.command.SAOCommand;
import sac.plugin.info.SACplugin.cooking.CookingListener;
import sac.plugin.info.SACplugin.cooking.CookingManager;
import sac.plugin.info.SACplugin.crystal.CrystalListener;
import sac.plugin.info.SACplugin.crystal.CrystalManager;
import sac.plugin.info.SACplugin.crystal.TpGateListener;
import sac.plugin.info.SACplugin.crystal.TpGateManager;
import sac.plugin.info.SACplugin.daily.DailyCommand;
import sac.plugin.info.SACplugin.daily.DailyManager;
import sac.plugin.info.SACplugin.daily.DailyNPCListener;
import sac.plugin.info.SACplugin.farming.SAOFarmManager;
import sac.plugin.info.SACplugin.farming.SAOFarmMenuListener;
import sac.plugin.info.SACplugin.fishing.SAOFishingListener;
import sac.plugin.info.SACplugin.fishing.SAOFishingManager;
import sac.plugin.info.SACplugin.fishing.FishingGearMenuListener;
import sac.plugin.info.SACplugin.item.NormalAttackManager;
import sac.plugin.info.SACplugin.item.SAOItemManager;
import sac.plugin.info.SACplugin.item.WeaponTypeManager;
import sac.plugin.info.SACplugin.limit.LimitManager;
import sac.plugin.info.SACplugin.menu.MenuListener;
import sac.plugin.info.SACplugin.mob.SAOMobListener;
import sac.plugin.info.SACplugin.mob.SAOMobManager;
import sac.plugin.info.SACplugin.mob.StatusEffectManager;
import sac.plugin.info.SACplugin.vote.VoteManager;
import sac.plugin.info.SACplugin.vote.VoteListener;
import sac.plugin.info.SACplugin.npc.SAOQuestListener;
import sac.plugin.info.SACplugin.npc.SAOQuestManager;
import sac.plugin.info.SACplugin.party.*;
import sac.plugin.info.SACplugin.shop.PlayerShopListener;
import sac.plugin.info.SACplugin.shop.PlayerShopManager;
import sac.plugin.info.SACplugin.skill.*;
import sac.plugin.info.SACplugin.storage.StorageListener;
import sac.plugin.info.SACplugin.storage.StorageManager;
import sac.plugin.info.SACplugin.survival.SafeZoneCommand;
import sac.plugin.info.SACplugin.survival.SafeZoneManager;
import sac.plugin.info.SACplugin.survival.SafeZoneRemoveCommand;
import sac.plugin.info.SACplugin.survival.SurvivalManager;
import sac.plugin.info.SACplugin.task.SAOSpawnerTask;
import sac.plugin.info.SACplugin.task.TimeManager;
import sac.plugin.info.SACplugin.treasure.*;
import sac.plugin.info.SACplugin.ui.SAOScoreboard;
import sac.plugin.info.SACplugin.util.Reloadable;
import sac.plugin.info.SACplugin.network.PlayerDataChannel;
import sac.plugin.info.SACplugin.network.MapShareChannel;

import java.util.ArrayList;
import java.util.List;

public class SACplugin extends JavaPlugin {
    private boolean spawnerEnabled = true;
    private PlayerManager playerManager;
    private DeathBoardManager deathBoardManager;
    private SAOScoreboard saoScoreboard;
    private SAOItemManager saoItemManager;
    private WeaponTypeManager weaponTypeManager;
    private UpgradeMenuListener upgradeMenuListener;
    private SAOMobManager saoMobManager;
    private SAOSellListener saoSellListener;
    private SwordSkillManager swordSkillManager;
    private SAOAGIListener agiListener;
    private SAOSTRListener strListener;
    private SAOSkillListener skillListener;
    private SAOQuestManager questManager;
    private SAOQuestListener questListener;
    private DailyManager dailyManager;
    private ForgeManager forgeManager;
    private ForgeMinigameListener forgeMinigameListener;
    private AccessoryManager accessoryManager;
    private TutorialJsonManager tutorialJsonManager;
    private TutorialFlowListener tutorialFlowListener;
    private StatusEffectManager statusEffectManager;
    private VoteManager voteManager;
    private ArmorForgeManager armorForgeManager;
    private CookingManager cookingManager;
    private PartyManager partyManager;
    private PartyMenuListener partyMenuListener;
    private FriendManager friendManager;
    private CrystalManager crystalManager;
    private BossManager bossManager;
    private RaidManager raidManager;
    private EndGameManager endGameManager;
    private ParryManager parryManager;
    private StorageManager storageManager;
    private SAOFishingManager saofishingManager;
    private FishingGearMenuListener fishingGearMenuListener;
    private SAOFarmMenuListener farmMenuListener;
    private SAOFarmManager farmManager;
    private PlayerShopManager playerShopManager;
    private LimitManager limitManager;
    private TpGateManager tpGateManager;
    private BossAIRegistry bossAIRegistry;
    private BossBarManager bossBarManager;
    private TimeManager timeManager;
    private sac.plugin.info.SACplugin.life.VitalityManager vitalityManager;
    private TreasureManager treasureManager;
    private SAOMobListener saoMobListener;
    private SurvivalManager survivalManager;
    private SafeZoneManager safeZoneManager;
    private NormalAttackManager normalAttackManager;
    // ==========================================
    // ★追加: /sacreload から一括で再読み込みできるようにするための登録リスト。
    // これから新しいマネージャーを増やす時は、implements Reloadable してここに登録するだけでよい。
    // ==========================================
    private final List<Reloadable> reloadables = new ArrayList<>();

    @Override
    public void onEnable() {
        // サーバー起動時に、バニラの全レシピ（クラフト、かまど等すべて）を消去！
        Bukkit.getServer().clearRecipes();

        getLogger().info("バニラのクラフトレシピを完全に消去しました。");

        if (!getDataFolder().exists()) getDataFolder().mkdir();

        PlayerDataChannel playerDataChannel = new PlayerDataChannel(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, PlayerDataChannel.CHANNEL, playerDataChannel);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PlayerDataChannel.CHANNEL);

        MapShareChannel mapShareChannel = new MapShareChannel(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, MapShareChannel.CHANNEL, mapShareChannel);
        getServer().getMessenger().registerOutgoingPluginChannel(this, MapShareChannel.CHANNEL);
        getServer().getPluginManager().registerEvents(mapShareChannel, this);

        // マネジャークラスの初期化
        this.playerManager = new PlayerManager(this);
        this.deathBoardManager = new DeathBoardManager(this);
        this.saoScoreboard = new SAOScoreboard(this);
        this.saoItemManager = new SAOItemManager(this);
        this.weaponTypeManager = new WeaponTypeManager(this);
        reloadables.add(this.saoItemManager);
        reloadables.add(this.weaponTypeManager);
        // ★通常攻撃: weapon_types.json の "normalAttack" を読む（WeaponTypeManager の後に作る）
        this.normalAttackManager = new NormalAttackManager(this);
        this.normalAttackManager.load();
        this.saoMobManager = new SAOMobManager(this);
        reloadables.add(this.saoMobManager);
        this.saoSellListener = new SAOSellListener(this);
        this.swordSkillManager = new SwordSkillManager(this);
        reloadables.add(this.swordSkillManager);
        this.agiListener = new SAOAGIListener(this);
        this.strListener = new SAOSTRListener(this);
        this.skillListener = new SAOSkillListener(this);
        this.questManager = new SAOQuestManager(this);
        this.questListener = new SAOQuestListener(this);
        // ★変更: DailyManagerの初期化処理がgetGeneralItem()経由でCookingManagerを参照するため、
        // CookingManagerを先に生成しておく(起動時NullPointerException対策)
        this.cookingManager = new CookingManager(this);
        this.dailyManager = new DailyManager(this);
        reloadables.add(this.questManager);
        reloadables.add(this.dailyManager);
        this.forgeManager = new ForgeManager(this);
        this.forgeMinigameListener = new ForgeMinigameListener(this);
        this.armorForgeManager = new ArmorForgeManager(this);
        this.accessoryManager = new AccessoryManager(this);
        this.tutorialJsonManager = new TutorialJsonManager(this);
        this.tutorialFlowListener = new TutorialFlowListener(this);
        this.statusEffectManager = new StatusEffectManager(this);
        this.voteManager = new VoteManager(this);
        reloadables.add(this.voteManager);
        reloadables.add(this.forgeManager);
        reloadables.add(this.armorForgeManager);
        reloadables.add(this.cookingManager);
        reloadables.add(this.accessoryManager);
        reloadables.add(this.tutorialJsonManager);
        this.partyManager = new PartyManager(this);
        this.partyMenuListener = new PartyMenuListener(this);
        this.friendManager = new FriendManager(this);
        this.crystalManager = new CrystalManager(this);
        this.bossManager = new BossManager(this);
        this.raidManager = new RaidManager(this);
        this.endGameManager = new EndGameManager(this);
        reloadables.add(this.crystalManager);
        reloadables.add(this.bossManager);
        reloadables.add(this.endGameManager);
        this.parryManager = new ParryManager(this);
        this.storageManager = new StorageManager(this);
        this.saofishingManager = new SAOFishingManager(this);
        reloadables.add(this.saofishingManager);
        this.fishingGearMenuListener = new FishingGearMenuListener(this);
        this.farmManager = new SAOFarmManager(this);
        reloadables.add(this.farmManager);
        this.farmMenuListener = new SAOFarmMenuListener(this);
        this.playerShopManager = new PlayerShopManager(this);
        this.limitManager = new LimitManager(this);
        this.tpGateManager = new TpGateManager(this);
        reloadables.add(this.limitManager);
        reloadables.add(this.tpGateManager);
        this.bossAIRegistry = new BossAIRegistry(this);
        this.bossBarManager = new BossBarManager(this);
        this.timeManager = new TimeManager(this);
        this.treasureManager = new TreasureManager(this);
        this.saoMobListener = new SAOMobListener(this);
        this.survivalManager = new SurvivalManager(this);
        this.survivalManager.start();
        this.safeZoneManager = new SafeZoneManager(this);
        reloadables.add(this.safeZoneManager);

        new SAOSpawnerTask(this).runTaskTimer(this, 0L, 20L);
        this.vitalityManager = new sac.plugin.info.SACplugin.life.VitalityManager(this);
        getServer().getPluginManager().registerEvents(this.vitalityManager, this);
        this.vitalityManager.start();
        this.timeManager.runTaskTimer(this, 0L, 1L); // ★修正: フィールドと同じインスタンスを起動
        new sac.plugin.info.SACplugin.skill.WeaponSpeedTask(this).runTaskTimer(this, 0L, 5L);

        getCommand("sac").setExecutor(new SAOCommand(this));
        getCommand("skillpoint").setExecutor(new SAOCommand(this));
        if (getCommand("appraisalnpc") != null) {
            getCommand("appraisalnpc").setExecutor(new AppraisalListener.SpawnCommand(this));
        }
        if (getCommand("accessoryforge") != null) {
            getCommand("accessoryforge").setExecutor(new AccessoryForgeListener.SpawnCommand(this));
        }
        if (getCommand("deathboard") != null) {
            getCommand("deathboard").setExecutor(new DeathBoardManager.Command(this));
        }
        getCommand("sac").setTabCompleter(new SAOCommand(this));
        getCommand("tutorial").setExecutor(new TutorialCommand(this));
        getCommand("partyaccept").setExecutor(new PartyCommand(this));
        getCommand("partydeny").setExecutor(new PartyCommand(this));
        getCommand("friendaccept").setExecutor(new FriendCommand(this));
        getCommand("frienddeny").setExecutor(new FriendCommand(this));
        getCommand("bosstp").setExecutor(new BossTeleportCommand(this));
        getCommand("bossremove").setExecutor(new BossRemoveCommand(this));
        getCommand("treasure").setExecutor(new TreasureCommand(this));
        getCommand("treasureremove").setExecutor(new TreasureRemoveCommand(this));
        getCommand("treasureresetall").setExecutor(new TreasureResetAllCommand(this));
        getCommand("safezone").setExecutor(new SafeZoneCommand(this));
        getCommand("safezoneremove").setExecutor(new SafeZoneRemoveCommand(this));
        getCommand("daily").setExecutor(new DailyCommand(this));
        getCommand("sacreload").setExecutor(new SACReloadCommand(this));

        // ★追加: 1分ごとに現実時間4時リセットをまたいだかチェックし、必要ならデイリーを再抽選する
        org.bukkit.Bukkit.getScheduler().runTaskTimer(this, () -> this.dailyManager.checkAndRollIfNeeded(), 20L * 60L, 20L * 60L);

        getLogger().info("SACプラグインが正常に起動しました！");

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        getServer().getPluginManager().registerEvents(new DeathListener(this), this);
        getServer().getPluginManager().registerEvents(new QuitListener(this), this);
        getServer().getPluginManager().registerEvents(new HealthListener(this), this);
        getServer().getPluginManager().registerEvents(new SAODamageListener(this), this);
        getServer().getPluginManager().registerEvents(this.saoMobListener, this);
        getServer().getPluginManager().registerEvents(new SAOSpawnerListener(this), this);
        getServer().getPluginManager().registerEvents(new SAOShopListener(this), this);
        getServer().getPluginManager().registerEvents(this.saoSellListener, this);
        getServer().getPluginManager().registerEvents(this.strListener, this);
        getServer().getPluginManager().registerEvents(this.agiListener, this);
        getServer().getPluginManager().registerEvents(this.skillListener, this);
        getServer().getPluginManager().registerEvents(new SkillMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new SAOFoodListener(this), this);
        getServer().getPluginManager().registerEvents(this.questListener, this);
        getServer().getPluginManager().registerEvents(new DailyNPCListener(this), this);
        getServer().getPluginManager().registerEvents(new TutorialListener(this), this);
        getServer().getPluginManager().registerEvents(this.tutorialFlowListener, this);
        getServer().getPluginManager().registerEvents(this.statusEffectManager, this);

        // ==========================================
        // ★変更: プラグイン名(plugin.ymlのname)はビルドによって「NuVotifier」以外の
        // 名前になっていることがあるため、名前ではなく実際にVotifierEventクラスが
        // クラスパス上に存在するかどうかで判定する(こちらの方が確実)。
        // ==========================================
        boolean votifierAvailable;
        try {
            Class.forName("com.vexsoftware.votifier.model.VotifierEvent");
            votifierAvailable = true;
        } catch (ClassNotFoundException e) {
            votifierAvailable = false;
        }

        if (votifierAvailable) {
            getServer().getPluginManager().registerEvents(new VoteListener(this), this);
            getLogger().info("[SAO] Votifier系プラグインを検知しました。投票報酬システムを有効化します。");
        } else {
            getLogger().warning("[SAO] Votifier系プラグインが見つかりません。投票報酬システムは無効化されています。");
        }
        getServer().getPluginManager().registerEvents(new TutorialStepListener(this), this);
        getServer().getPluginManager().registerEvents(new ForgeListener(this), this);
        getServer().getPluginManager().registerEvents(this.forgeMinigameListener, this);
        getServer().getPluginManager().registerEvents(new ArmorForgeListener(this), this);
        getServer().getPluginManager().registerEvents(new AppraisalListener(this), this);
        getServer().getPluginManager().registerEvents(new AccessoryForgeListener(this), this);
        this.upgradeMenuListener = new UpgradeMenuListener(this);
        reloadables.add(this.upgradeMenuListener);
        getServer().getPluginManager().registerEvents(this.upgradeMenuListener, this);
        getServer().getPluginManager().registerEvents(new RepairMenuListener(this),this);
        getServer().getPluginManager().registerEvents(new CookingListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(this.partyMenuListener, this);
        getServer().getPluginManager().registerEvents(new PartyChatListener(this), this);
        getServer().getPluginManager().registerEvents(new CrystalListener(this), this);
        getServer().getPluginManager().registerEvents(new BossTeleportListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockProtectListener(this), this);
        getServer().getPluginManager().registerEvents(parryManager, this);
        getServer().getPluginManager().registerEvents(new StorageListener(this), this);
        getServer().getPluginManager().registerEvents(new SAOStepListener(this), this);
        getServer().getPluginManager().registerEvents(new SAOFishingListener(this), this);
        getServer().getPluginManager().registerEvents(this.fishingGearMenuListener, this);
        if (getCommand("fishingspot") != null) {
            getCommand("fishingspot").setExecutor(new SAOFishingListener.SpotSpawnCommand());
        }
        getServer().getPluginManager().registerEvents(new SAOFarmingListener(this), this);
        getServer().getPluginManager().registerEvents(farmMenuListener, this);
        getServer().getPluginManager().registerEvents(new PlayerShopListener(this), this);
        getServer().getPluginManager().registerEvents(new TpGateListener(this), this);
        getServer().getPluginManager().registerEvents(new TreasureListener(this), this);
        getServer().getPluginManager().registerEvents(new WeaponReachListener(this), this);
        getServer().getPluginManager().registerEvents(new NormalAttackListener(this), this);

        // ★起動時にプレイヤーショップのワールド同期を実行
        // ワールドエンティティが完全にロードされるまで少し待つ
        org.bukkit.Bukkit.getScheduler().runTaskLater(this, () -> {
            this.playerShopManager.syncWithWorld();
        }, 40L); // 2秒後
    }

    private void saoItemManager() {
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }
    public DeathBoardManager getDeathBoardManager() {
        return deathBoardManager;
    }
    public SAOScoreboard getSaoScoreboard() {
        return saoScoreboard;
    }
    public SAOItemManager getSaoItemManager() { // 追加
        return saoItemManager;
    }
    public WeaponTypeManager getWeaponTypeManager() { return weaponTypeManager; }
    public NormalAttackManager getNormalAttackManager() { return normalAttackManager; }
    public UpgradeMenuListener getUpgradeMenuListener() { return upgradeMenuListener; }
    public SAOMobManager getSaoMobManager() { return saoMobManager; }
    public SAOSellListener getSaoSellListener() {return saoSellListener;}
    public SwordSkillManager getSwordSkillManager() {return swordSkillManager;}
    public SAOAGIListener getAgiListener() {return agiListener;}
    public SAOSTRListener getStrListener() {return strListener;}
    public SAOSkillListener getSkillListener() {return this.skillListener;}
    public SAOQuestManager getQuestManager() {return questManager;}
    public SAOQuestListener getQuestListener() {return questListener;}
    public DailyManager getDailyManager() {return dailyManager;}
    public List<Reloadable> getReloadables() { return reloadables; }
    public ForgeManager getForgeManager() {return forgeManager;}
    public ForgeMinigameListener getForgeMinigameListener() {return forgeMinigameListener;}
    public AccessoryManager getAccessoryManager() {return accessoryManager;}
    public TutorialJsonManager getTutorialJsonManager() {return tutorialJsonManager;}
    public TutorialFlowListener getTutorialFlowListener() {return tutorialFlowListener;}
    public StatusEffectManager getStatusEffectManager() {return statusEffectManager;}
    public VoteManager getVoteManager() {return voteManager;}
    public ArmorForgeManager getArmorForgeManager() {return armorForgeManager;}
    public CookingManager getCookingManager() { return cookingManager; }
    public PartyManager getPartyManager() { return partyManager; }
    public PartyMenuListener getPartyMenuListener() { return partyMenuListener; }
    public FriendManager getFriendManager() { return friendManager; }
    public CrystalManager getCrystalManager() { return crystalManager; }
    public BossManager getBossManager() { return bossManager; }
    public RaidManager getRaidManager() { return raidManager; }
    public EndGameManager getEndGameManager() {return endGameManager;}
    public ParryManager getParryManager() { return parryManager; }
    public StorageManager getStorageManager() { return storageManager; }
    public SAOFishingManager getFishingManager() { return saofishingManager; }
    public FishingGearMenuListener getFishingGearMenuListener() { return fishingGearMenuListener; }
    public SAOFarmManager getFarmManager() {return this.farmManager;}
    public SAOFarmMenuListener getFarmMenuListener() {return this.farmMenuListener;}
    public PlayerShopManager getPlayerShopManager() { return playerShopManager; }
    public LimitManager getLimitManager() { return limitManager; }
    public TpGateManager getTpGateManager() { return tpGateManager; }
    public BossAIRegistry getBossAIRegistry() { return bossAIRegistry; }
    public BossBarManager getBossBarManager() { return bossBarManager; }
    public TimeManager getTimeManager() { return timeManager; }
    public sac.plugin.info.SACplugin.life.VitalityManager getVitalityManager() { return vitalityManager; }
    public TreasureManager getTreasureManager() { return treasureManager; }
    public SurvivalManager getSurvivalManager() { return survivalManager; }
    public SafeZoneManager getSafeZoneManager() { return safeZoneManager; }
    public SAOMobListener getSaoMobListener() { return saoMobListener; }

    public boolean isSpawnerEnabled() { return spawnerEnabled; }
    public void setSpawnerEnabled(boolean enabled) { this.spawnerEnabled = enabled; }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, PlayerDataChannel.CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, PlayerDataChannel.CHANNEL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, MapShareChannel.CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, MapShareChannel.CHANNEL);
        // サーバーにいる全プレイヤーをループ処理でキックする
        for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            player.kickPlayer("§cサーバーが再起動またはリロードされています。\n§f少し待ってから再接続してください。");
        }

        // ★追加: レイドの登録状況・ダメージ記録を再起動をまたいでも復元できるよう保存する
        if (this.raidManager != null) {
            this.raidManager.saveState();
        }

        // （もしデータ保存など終了時にやりたいことがあればここに書きます）

        getLogger().info("SACプラグインが正常に終了・リロードされました。");
    }
}