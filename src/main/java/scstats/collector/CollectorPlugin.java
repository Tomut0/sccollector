package scstats.collector;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanRequest;
import net.sacredlabyrinth.phaed.simpleclans.Request;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.events.ClanBalanceUpdateEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.CreateClanEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.DisbandClanEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.PlayerJoinedClanEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.PlayerKickedClanEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.RequestEvent;
import net.sacredlabyrinth.phaed.simpleclans.events.TagChangeEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import static scstats.collector.Snapshot.id;

public final class CollectorPlugin extends JavaPlugin implements Listener {
    private final Map<String, Request> invites = new LinkedHashMap<>();
    private SimpleClans simpleClans;
    private volatile Feed feed;
    private Snapshot snapshot;
    private IngestClient client;
    private BukkitTask task;
    private CompletableFuture<Boolean> sending;
    private Feed sentFeed;
    private List<Map<String, Object>> sentEvents;

    @Override
    public void onEnable() {
        simpleClans = (SimpleClans) getServer().getPluginManager().getPlugin("SimpleClans");
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("scstats").setTabCompleter((sender, command, alias, args) -> Collections.emptyList());
        String endpoint = getConfig().getString("endpoint", "");
        String key = getConfig().getString("api-key", "");
        if (endpoint.isEmpty() && key.isEmpty()) {
            getLogger().info("Waiting for Ingest configuration in config.yml or /scstats config apply.");
            return;
        }
        try {
            start(new IngestClient(endpoint, key));
        } catch (IllegalArgumentException invalid) {
            getLogger().warning(invalid.getMessage());
        }
    }

    private void start(IngestClient newClient) {
        if (task != null) {
            task.cancel();
        }
        client = newClient;
        feed = new Feed();
        snapshot = new Snapshot(simpleClans, feed);
        long period = Math.max(5, getConfig().getInt("interval-seconds")) * 20L;
        task = getServer().getScheduler().runTaskTimer(this, this::publish, 1L, period);
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 6 || !args[0].equals("config") || !args[1].equals("apply")
                || !args[2].equals("--endpoint") || !args[4].equals("--api-key")) {
            return false;
        }
        try {
            IngestClient newClient = new IngestClient(args[3], args[5]);
            saveConnection(args[3], args[5]);
            start(newClient);
            sender.sendMessage("SCCollector saved the Ingest configuration and scheduled a snapshot.");
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage(invalid.getMessage());
        } catch (IOException saveFailed) {
            sender.sendMessage("SCCollector could not save config.yml.");
        }
        return true;
    }

    private void saveConnection(String endpoint, String key) throws IOException {
        Path configPath = getDataFolder().toPath().resolve("config.yml");
        YamlConfiguration updated = new YamlConfiguration();
        try {
            updated.load(configPath.toFile());
        } catch (InvalidConfigurationException invalid) {
            throw new IOException("Invalid config.yml", invalid);
        }
        updated.set("endpoint", endpoint);
        updated.set("api-key", key);
        Path temporary = Files.createTempFile(configPath.getParent(), "config-", ".yml");
        try {
            updated.save(temporary.toFile());
            Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        reloadConfig();
    }

    private void publish() {
        if (sending != null) {
            if (!sending.isDone()) {
                return;
            }
            if (sending.join()) {
                sentFeed.acknowledge(sentEvents);
            }
            sending = null;
            sentFeed = null;
            sentEvents = null;
        }
        invites.keySet().removeIf(target -> !simpleClans.getRequestManager().hasRequest(target));
        String serverId = getConfig().getString("server-id", "").trim();
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> data;
        try {
            data = snapshot.capture(serverId, getConfig().getBoolean("collect-claims"), invites.values(), events);
        } catch (RuntimeException captureFailed) {
            getLogger().log(Level.WARNING, "Could not collect a SimpleClans snapshot.", captureFailed);
            return;
        }
        IngestClient destination = client;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        sentFeed = feed;
        sentEvents = events;
        sending = result;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean accepted = false;
            try {
                destination.send(data);
                accepted = true;
            } catch (IOException | RuntimeException sendFailed) {
                String detail = sendFailed.getMessage();
                if (detail == null || !detail.startsWith("Ingest ")) {
                    detail = sendFailed.getClass().getSimpleName();
                }
                getLogger().warning("Snapshot delivery failed (" + detail + "). Will retry on the next interval.");
            } finally {
                result.complete(accepted);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinedClanEvent event) {
        membership("player.joinedClan", event.getClan(), event.getClanPlayer().getUniqueId(), event.getClanPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerKickedClanEvent event) {
        membership("player.leftClan", event.getClan(), event.getClanPlayer().getUniqueId(), event.getClanPlayer().getName());
    }

    private void membership(String type, Clan clan, UUID uuid, String name) {
        Feed current = feed;
        if (current != null) {
            current.record(type, "clanExternalId", id(clan), "playerUuid", uuid == null ? null : uuid.toString(), "playerName", name);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreate(CreateClanEvent event) {
        if (feed != null) {
            feed.record("clan.created", "clanExternalId", id(event.getClan()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(DisbandClanEvent event) {
        if (feed != null) {
            feed.record("clan.disbanded", "clanExternalId", id(event.getClan()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTagChange(TagChangeEvent event) {
        Feed current = feed;
        Clan clan = event.getClan();
        String oldId = id(clan);
        if (current != null) {
            getServer().getScheduler().runTask(this, () -> {
                String newId = id(clan);
                if (feed == current && !oldId.equals(newId)) {
                    current.record("clan.tagChanged", "clanExternalId", oldId, "newClanExternalId", newId);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBalance(ClanBalanceUpdateEvent event) {
        Feed current = feed;
        double amount = event.getNewBalance() - event.getBalance();
        if (current != null && Double.isFinite(amount) && amount != 0
                && event.getCause() != ClanBalanceUpdateEvent.Cause.LOADING) {
            current.record("clan.balanceChanged", "clanExternalId", id(event.getClan()),
                    "amount", amount, "cause", event.getCause().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvite(RequestEvent event) {
        Request request = event.getRequest();
        if (request.getType() == ClanRequest.INVITE) {
            invites.put(request.getTarget().toLowerCase(Locale.ROOT), request);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (feed == null || killer == null || killer.equals(victim)
                || victim.hasMetadata("NPC") || killer.hasMetadata("NPC")) {
            return;
        }
        Clan attacking = simpleClans.getClanManager().getClanByPlayerUniqueId(killer.getUniqueId());
        Clan defending = simpleClans.getClanManager().getClanByPlayerUniqueId(victim.getUniqueId());
        feed.record("player.pvpKill", "killerUuid", killer.getUniqueId().toString(), "killerName", killer.getName(),
                "victimUuid", victim.getUniqueId().toString(), "victimName", victim.getName(),
                "killerClanExternalId", attacking == null ? null : id(attacking),
                "victimClanExternalId", defending == null ? null : id(defending),
                "killType", killType(attacking, defending));
    }

    private String killType(Clan attacking, Clan defending) {
        if (attacking == null || defending == null || !attacking.isVerified() || !defending.isVerified()) {
            return "civilian";
        }
        if (attacking.isRival(id(defending))) {
            return "rival";
        }
        return attacking.equals(defending) || attacking.isAlly(id(defending)) ? "ally" : "neutral";
    }
}
