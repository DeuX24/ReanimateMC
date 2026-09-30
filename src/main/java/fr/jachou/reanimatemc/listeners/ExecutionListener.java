package fr.jachou.reanimatemc.listeners;

import fr.jachou.reanimatemc.ReanimateMC;
import fr.jachou.reanimatemc.managers.KOManager;
import fr.jachou.reanimatemc.utils.Utils;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ExecutionListener implements Listener {
    private final KOManager koManager;
    private final Map<UUID, Attempt> attempts = new HashMap<>(); // victim -> current execution attempt

    public ExecutionListener(KOManager koManager) {
        this.koManager = koManager;
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Player))
            return;

        Player damager = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();

        if (!koManager.isKO(victim))
            return;
        // Nobody can hit or execute a player while they are being carried.
        if (ReanimateMC.getInstance().getCarryManager().isCarried(victim)) {
            event.setCancelled(true);
            return;
        }
        if (!ReanimateMC.getInstance().getConfig().getBoolean("execution.enabled"))
            return;

        event.setCancelled(true);

        if (!damager.hasPermission("reanimatemc.execute")) return;

        // Execution takes several hits in a row, so a single stray click never kills anyone.
        int required = Math.max(1, ReanimateMC.getInstance().getConfig().getInt("execution.hits_required", 3));
        long windowMs = (long) (ReanimateMC.getInstance().getConfig().getDouble("execution.hit_window_seconds", 2.0) * 1000);
        long now = System.currentTimeMillis();

        Attempt attempt = attempts.get(victim.getUniqueId());
        if (attempt != null && now - attempt.firstHit > windowMs) {
            attempts.remove(victim.getUniqueId());
            attempt = null;
        }
        if (attempt != null && !attempt.executioner.equals(damager.getUniqueId())) {
            damager.sendMessage(ChatColor.RED + ReanimateMC.lang.get("execution_in_progress"));
            return;
        }
        if (attempt == null) {
            attempt = new Attempt(damager.getUniqueId(), now);
            attempts.put(victim.getUniqueId(), attempt);
        }
        attempt.hits++;

        if (attempt.hits >= required) {
            attempts.remove(victim.getUniqueId());
            // Run after this damage event finishes: killing the player inside it makes them die twice.
            org.bukkit.Bukkit.getScheduler().runTask(ReanimateMC.getInstance(), () -> {
                if (koManager.isKO(victim)) koManager.execute(victim);
            });
            return;
        }
        Utils.sendActionBar(damager, ChatColor.RED + ReanimateMC.lang.get("execution_progress",
                "player", victim.getName(), "hits", String.valueOf(attempt.hits), "required", String.valueOf(required)));
    }

    private static final class Attempt {
        final UUID executioner;
        final long firstHit;
        int hits;

        Attempt(UUID executioner, long firstHit) {
            this.executioner = executioner;
            this.firstHit = firstHit;
        }
    }
}
