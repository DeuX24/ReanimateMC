package fr.jachou.reanimatemc.managers;

import fr.jachou.reanimatemc.ReanimateMC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lets a player pick up a K.O.'d player and carry them on their shoulders.
 *
 * <p>The K.O.'d player rides the carrier as a passenger. The carrier is slowed
 * while carrying. Carrying ends when the carrier crouches, when either player
 * dies, logs out, changes world or teleports, when the carrier is knocked out,
 * or when the carried player is revived.
 */
public class CarryManager {

    private final ReanimateMC plugin;
    private final KOManager koManager;
    /** carrier -> carried */
    private final Map<UUID, UUID> carrying = new HashMap<>();
    /** carried -> carrier */
    private final Map<UUID, UUID> carriedBy = new HashMap<>();
    /** Carried players currently being released on purpose, so the dismount is allowed. */
    private final Set<UUID> releasing = new HashSet<>();
    private BukkitTask watchdog;

    public CarryManager(ReanimateMC plugin, KOManager koManager) {
        this.plugin = plugin;
        this.koManager = koManager;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("carry.enabled", true);
    }

    public boolean isCarrying(Player player) {
        return carrying.containsKey(player.getUniqueId());
    }

    public boolean isCarried(Player player) {
        return carriedBy.containsKey(player.getUniqueId());
    }

    public boolean isReleasing(Player player) {
        return releasing.contains(player.getUniqueId());
    }

    /** Tries to pick up {@code target}. Sends the carrier a message explaining any refusal. */
    public boolean pickUp(Player carrier, Player target) {
        if (!isEnabled()) return false;
        if (koManager.isKO(carrier)) {
            carrier.sendMessage(ChatColor.RED + ReanimateMC.lang.get("carry_cannot_while_ko"));
            return false;
        }
        if (isCarrying(carrier)) {
            carrier.sendMessage(ChatColor.RED + ReanimateMC.lang.get("carry_already_carrying"));
            return false;
        }
        if (isCarried(target) || target.isInsideVehicle()) {
            carrier.sendMessage(ChatColor.RED + ReanimateMC.lang.get("carry_already_carried"));
            return false;
        }
        if (!carrier.getPassengers().isEmpty()) {
            carrier.sendMessage(ChatColor.RED + ReanimateMC.lang.get("carry_hands_full"));
            return false;
        }

        // A carried player can't keep channeling a self-revive on the move.
        koManager.cancelSelfRevive(target, true);

        if (!carrier.addPassenger(target)) {
            carrier.sendMessage(ChatColor.RED + ReanimateMC.lang.get("carry_failed"));
            return false;
        }

        carrying.put(carrier.getUniqueId(), target.getUniqueId());
        carriedBy.put(target.getUniqueId(), carrier.getUniqueId());

        int level = plugin.getConfig().getInt("carry.carrier_slowness_level", 1);
        if (level > 0) {
            carrier.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    PotionEffect.INFINITE_DURATION, level - 1, false, false, false));
        }

        carrier.sendMessage(ChatColor.GREEN + ReanimateMC.lang.get("carry_pickup", "player", target.getName()));
        target.sendMessage(ChatColor.YELLOW + ReanimateMC.lang.get("carry_carried_by", "player", carrier.getName()));
        startWatchdog();
        return true;
    }

    /** Puts down whoever {@code carrier} is carrying, next to them. */
    public void dropByCarrier(Player carrier, boolean notify) {
        UUID targetId = carrying.get(carrier.getUniqueId());
        if (targetId == null) return;
        Player target = Bukkit.getPlayer(targetId);
        release(carrier.getUniqueId(), targetId, carrier, target, notify);
    }

    /** Releases {@code target} from whoever is carrying them. */
    public void releaseCarried(Player target, boolean notify) {
        UUID carrierId = carriedBy.get(target.getUniqueId());
        if (carrierId == null) return;
        release(carrierId, target.getUniqueId(), Bukkit.getPlayer(carrierId), target, notify);
    }

    private void release(UUID carrierId, UUID targetId, Player carrier, Player target, boolean notify) {
        carrying.remove(carrierId);
        carriedBy.remove(targetId);

        if (carrier != null) {
            removeCarrySlowness(carrier);
            if (target != null && carrier.getPassengers().contains(target)) {
                releasing.add(targetId);
                try {
                    carrier.removePassenger(target);
                } finally {
                    releasing.remove(targetId);
                }
                if (target.isOnline() && !target.isDead()) {
                    Location drop = carrier.getLocation().clone();
                    drop.setPitch(target.getLocation().getPitch());
                    drop.setYaw(target.getLocation().getYaw());
                    target.teleport(drop);
                }
            }
            if (notify && carrier.isOnline()) {
                String name = target != null ? target.getName() : "?";
                carrier.sendMessage(ChatColor.YELLOW + ReanimateMC.lang.get("carry_drop", "player", name));
            }
        }
        if (notify && target != null && target.isOnline()) {
            String name = carrier != null ? carrier.getName() : "?";
            target.sendMessage(ChatColor.YELLOW + ReanimateMC.lang.get("carry_dropped", "player", name));
        }
        if (carrying.isEmpty()) stopWatchdog();
    }

    private void removeCarrySlowness(Player carrier) {
        PotionEffect effect = carrier.getPotionEffect(PotionEffectType.SLOWNESS);
        int level = plugin.getConfig().getInt("carry.carrier_slowness_level", 1);
        if (effect != null && effect.isInfinite() && effect.getAmplifier() == level - 1) {
            carrier.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    /** Releases everyone, e.g. on plugin disable. */
    public void releaseAll() {
        for (Map.Entry<UUID, UUID> e : new HashMap<>(carrying).entrySet()) {
            release(e.getKey(), e.getValue(), Bukkit.getPlayer(e.getKey()), Bukkit.getPlayer(e.getValue()), false);
        }
    }

    /** Catches anything the event listeners miss: invalid players, lost passengers, ended K.O. */
    private void startWatchdog() {
        if (watchdog != null) return;
        watchdog = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, UUID> e : new HashMap<>(carrying).entrySet()) {
                Player carrier = Bukkit.getPlayer(e.getKey());
                Player target = Bukkit.getPlayer(e.getValue());
                boolean valid = carrier != null && carrier.isOnline() && !carrier.isDead()
                        && target != null && target.isOnline() && !target.isDead()
                        && koManager.isKO(target) && !koManager.isKO(carrier)
                        && carrier.getPassengers().contains(target);
                if (!valid) release(e.getKey(), e.getValue(), carrier, target, true);
            }
        }, 10L, 10L);
    }

    private void stopWatchdog() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }
}
