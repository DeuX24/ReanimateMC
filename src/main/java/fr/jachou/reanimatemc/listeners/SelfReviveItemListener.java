package fr.jachou.reanimatemc.listeners;

import fr.jachou.reanimatemc.ReanimateMC;
import fr.jachou.reanimatemc.managers.KOManager;
import fr.jachou.reanimatemc.managers.ReviveChainManager;
import fr.jachou.reanimatemc.utils.Utils;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * With the revive chain on, a downed player revives themselves by right-clicking
 * while holding the item the self-revive costs (a golden apple, a totem...).
 * {@code /sr} keeps working as well. Items can't be eaten or used while down.
 */
public class SelfReviveItemListener implements Listener {

    private final KOManager koManager;
    /** Last hint time per player, so holding right-click doesn't flood the screen. */
    private final Map<UUID, Long> lastHint = new HashMap<>();

    public SelfReviveItemListener(KOManager koManager) {
        this.koManager = koManager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!koManager.isKO(player)) return;
        ReviveChainManager chain = ReanimateMC.getInstance().getReviveChainManager();
        if (!chain.isEnabled()) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;

        // Nothing gets eaten, drunk or placed while down.
        event.setCancelled(true);

        if (!ReanimateMC.getInstance().getConfig().getBoolean("self_revive.enabled", true)) return;
        if (!player.hasPermission("reanimatemc.selfrevive")) return;
        if (koManager.isChannelingSelfRevive(player)) return;

        ReviveChainManager.Cost cost = chain.getNextCost(player.getUniqueId(), true);
        if (cost == null) {
            hint(player, ReanimateMC.lang.get("revive_chain_no_selfrevive"));
            return;
        }
        if (cost.isFree() || item.getType() == cost.material()) {
            if (!throttled(player)) koManager.startSelfRevive(player);
            return;
        }
        hint(player, ReanimateMC.lang.get("revive_chain_selfrevive_wrong_item",
                "itemname", cost.itemName(), "item", cost.describe()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (koManager.isKO(event.getPlayer())) event.setCancelled(true);
    }

    private void hint(Player player, String message) {
        if (throttled(player)) return;
        player.sendMessage(ChatColor.RED + message);
        Utils.sendActionBar(player, ChatColor.RED + message);
    }

    /** True if this player already got a response in the last second (also dedupes main/off hand). */
    private boolean throttled(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastHint.get(player.getUniqueId());
        if (last != null && now - last < 1000) return true;
        lastHint.put(player.getUniqueId(), now);
        return false;
    }
}
