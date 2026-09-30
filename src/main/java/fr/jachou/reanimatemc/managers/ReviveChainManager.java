package fr.jachou.reanimatemc.managers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks how many times each player has been revived in a row and what the
 * next revive costs.
 *
 * <p>A chain is the run of revives a player gets while each one happens within
 * {@code revive_chain.window_minutes} of the previous one. Revive N of a chain
 * costs {@code revive_chain.costs[N-1]}; a self-revive costs the entry
 * {@code self_revive_tier_offset} further along. Once every entry is used the
 * player can no longer be downed and dies normally until the window runs out.
 * Real deaths do not reset the chain. Chains are saved to
 * {@code revive_chains.yml} so they survive logouts and restarts.
 */
public class ReviveChainManager {

    /** One revive price: {@code material == null} means free. */
    public record Cost(Material material, int amount) {
        public boolean isFree() {
            return material == null || amount <= 0;
        }

        public ItemStack toItemStack() {
            return new ItemStack(material, amount);
        }

        public String describe() {
            if (isFree()) return "free";
            return amount + "x " + material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private record Chain(int count, long lastReviveMillis) {}

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Chain> chains = new HashMap<>();

    public ReviveChainManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "revive_chains.yml");
        load();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("revive_chain.enabled", true);
    }

    private long windowMillis() {
        return plugin.getConfig().getLong("revive_chain.window_minutes", 15) * 60_000L;
    }

    private List<Cost> costs() {
        List<Cost> result = new ArrayList<>();
        for (String entry : plugin.getConfig().getStringList("revive_chain.costs")) {
            result.add(parseCost(entry));
        }
        return result;
    }

    private Cost parseCost(String entry) {
        String s = entry.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("free")) return new Cost(null, 0);
        String[] parts = s.split(":", 2);
        Material mat = Material.matchMaterial(parts[0].trim());
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Invalid amount in revive_chain.costs entry '" + entry + "', using 1.");
            }
        }
        if (mat == null) {
            plugin.getLogger().warning("Unknown material in revive_chain.costs entry '" + entry + "', treating it as free.");
            return new Cost(null, 0);
        }
        return new Cost(mat, Math.max(1, amount));
    }

    /** Revives already used in the player's current chain (0 once the window has passed). */
    public int getChainCount(UUID uuid) {
        Chain chain = chains.get(uuid);
        if (chain == null) return 0;
        if (System.currentTimeMillis() - chain.lastReviveMillis() > windowMillis()) return 0;
        return chain.count();
    }

    /** Milliseconds until the player's chain resets, or 0 if there is no active chain. */
    public long getMillisUntilReset(UUID uuid) {
        Chain chain = chains.get(uuid);
        if (chain == null || getChainCount(uuid) == 0) return 0;
        return Math.max(0, chain.lastReviveMillis() + windowMillis() - System.currentTimeMillis());
    }

    /**
     * Price of the player's next revive, or {@code null} if that kind of revive
     * is not allowed any more.
     *
     * @param selfRevive whether the player is reviving themselves
     */
    public Cost getNextCost(UUID uuid, boolean selfRevive) {
        List<Cost> costs = costs();
        int index = getChainCount(uuid);
        if (selfRevive) index += plugin.getConfig().getInt("revive_chain.self_revive_tier_offset", 1);
        if (index < 0 || index >= costs.size()) return null;
        return costs.get(index);
    }

    /** How many revives a chain allows in total. */
    public int getMaxRevives() {
        return costs().size();
    }

    public long getWindowMinutes() {
        return plugin.getConfig().getLong("revive_chain.window_minutes", 15);
    }

    /** True when not even a teammate can revive the player, so they should die instead of going down. */
    public boolean isExhausted(UUID uuid) {
        return getNextCost(uuid, false) == null;
    }

    /** Records a completed revive; each one restarts the window. */
    public void recordRevive(UUID uuid) {
        chains.put(uuid, new Chain(getChainCount(uuid) + 1, System.currentTimeMillis()));
        save();
    }

    /** Whether the player holds enough of the cost in their main hand. */
    public static boolean hasInMainHand(Player player, Cost cost) {
        if (cost.isFree()) return true;
        ItemStack hand = player.getInventory().getItemInMainHand();
        return hand != null && hand.getType() == cost.material() && hand.getAmount() >= cost.amount();
    }

    /** Removes the cost from the player's main hand. Returns false if they no longer have it. */
    public static boolean takeFromMainHand(Player player, Cost cost) {
        if (cost.isFree()) return true;
        if (!hasInMainHand(player, cost)) return false;
        ItemStack hand = player.getInventory().getItemInMainHand();
        int left = hand.getAmount() - cost.amount();
        if (left <= 0) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(left);
            player.getInventory().setItemInMainHand(hand);
        }
        return true;
    }

    private void load() {
        chains.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                chains.put(uuid, new Chain(players.getInt(key + ".count"), players.getLong(key + ".last_revive")));
            } catch (IllegalArgumentException ignored) {
                // Not a UUID; skip.
            }
        }
    }

    /** Writes all active chains to disk, dropping ones whose window has passed. */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        chains.entrySet().removeIf(e -> now - e.getValue().lastReviveMillis() > windowMillis());
        for (Map.Entry<UUID, Chain> e : chains.entrySet()) {
            String path = "players." + e.getKey();
            yaml.set(path + ".count", e.getValue().count());
            yaml.set(path + ".last_revive", e.getValue().lastReviveMillis());
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save revive_chains.yml: " + ex.getMessage());
        }
    }
}
