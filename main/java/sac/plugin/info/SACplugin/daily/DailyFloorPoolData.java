package sac.plugin.info.SACplugin.daily;


import java.util.ArrayList;
import java.util.List;

/**
 * daily_floors.json の1エントリに対応するデータクラス。
 * 各階層の「デイリーの抽選候補プール」を保持する。
 * 実際に毎日抽選されるのはこの中からランダムに1つだけ。
 */
public class DailyFloorPoolData {
    public int floor;                 // 階層番号
    public String world = "world";    // 参考情報（現状ロジックでは未使用、将来のワールド分け用）

    // 納品デイリーの対象アイテムIDの候補一覧（この中からランダムに1つ選ばれる）
    public List<String> deliveryItemIds = new ArrayList<>();
    // 討伐デイリーの対象MobIDの候補一覧
    public List<String> killMobIds = new ArrayList<>();
    // デイリー報酬のボーナスアイテムIDの候補一覧（討伐・納品・宝箱すべての種別で共通して使う）
    // 実際の抽選では、1つ前の階層のこのリストも候補プールに混ぜられる
    public List<String> rewardItemIds = new ArrayList<>();
    // 1回の抽選で決まるアイテム個数の範囲（3回抽選するので、同じアイテムが被れば合算される）
    public int rewardItemMinAmount = 1;
    public int rewardItemMaxAmount = 3;

    // 討伐・納品の必要数の範囲（この間でランダムに決まる）
    public int minAmount = 5;
    public int maxAmount = 10;

    // このフロアのデイリーおじさんの表示名（未指定なら"デイリーおじさん(第N層)"を自動生成）
    public String npcName;
}
