package fr.jachou.reanimatemc.listeners;

import fr.jachou.reanimatemc.api.PlayerKOEvent;
import fr.jachou.reanimatemc.api.PlayerReanimatedEvent;
import fr.jachou.reanimatemc.managers.CarryManager;
import fr.jachou.reanimatemc.managers.KOManager;
import fr.jachou.reanimatemc.utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Right-click (without crouching) a K.O.'d player to pick them up; crouch to put them down.
 * Crouch + right-click stays the revive gesture.
 */
public class CarryListener implements Listener {

    private final CarryManager carryManager;
    private final KOManager koManager;

    public CarryListener(CarryManager carryManager, KOManager koManager) {
        this.carryManager = carryManager;
        this.koManager = koManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (!carryManager.isEnabled()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Player target)) return;
        Player carrier = event.getPlayer();
        if (carrier.isSneaking()) return; // crouch + right-click is revive
        if (Utils.isNPC(target) || Utils.isNPC(carrier)) return;
        if (!koManager.isKO(target)) return;
        if (!carrier.hasPermission("reanimatemc.carry")) return;

        event.setCancelled(true);
        carryManager.pickUp(carrier, target);
    }

    @EventHandler
    public void onCarrierSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        if (carryManager.isCarrying(player)) carryManager.dropByCarrier(player, true);
    }

    /** The carried player can't hop off by crouching; only the carrier decides. */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player rider)) return;
        if (!carryManager.isCarried(rider) || carryManager.isReleasing(rider)) return;
        if (rider.isOnline() && !rider.isDead() && koManager.isKO(rider)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRevived(PlayerReanimatedEvent event) {
        Player revived = event.getPlayer();
        if (carryManager.isCarried(revived)) carryManager.releaseCarried(revived, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockedOut(PlayerKOEvent event) {
        Player player = event.getPlayer();
        if (carryManager.isCarrying(player)) carryManager.dropByCarrier(player, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        releaseEitherRole(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        releaseEitherRole(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        releaseEitherRole(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        // Our own drop teleports the carried player; leave that alone.
        if (carryManager.isReleasing(player)) return;
        if (carryManager.isCarrying(player)) {
            // Drop first: a player can't teleport with a passenger.
            carryManager.dropByCarrier(player, true);
        } else if (carryManager.isCarried(player)) {
            carryManager.releaseCarried(player, true);
        }
    }

    private void releaseEitherRole(Player player) {
        if (carryManager.isCarrying(player)) carryManager.dropByCarrier(player, true);
        if (carryManager.isCarried(player)) carryManager.releaseCarried(player, true);
    }
}
