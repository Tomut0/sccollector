package scstats.collector;

import net.sacredlabyrinth.phaed.simpleclans.Clan;
import net.sacredlabyrinth.phaed.simpleclans.ClanPlayer;
import net.sacredlabyrinth.phaed.simpleclans.Request;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;
import net.sacredlabyrinth.phaed.simpleclans.War;
import net.sacredlabyrinth.phaed.simpleclans.hooks.protection.Land;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Snapshot {
    private final SimpleClans simpleClans;
    private final Feed feed;
    private Set<String> verified;
    private Set<List<String>> activeWars;

    Snapshot(SimpleClans simpleClans, Feed feed) {
        this.simpleClans = simpleClans;
        this.feed = feed;
    }

    Map<String, Object> capture(String serverId, boolean collectClaims, Iterable<Request> pendingInvites,
                                List<Map<String, Object>> events) {
        String takenAt = Instant.now().toString();
        Map<UUID, Map<String, Object>> players = new LinkedHashMap<>();
        List<Map<String, Object>> clans = new ArrayList<>();
        List<Map<String, Object>> online = new ArrayList<>();
        List<Map<String, Object>> wars = new ArrayList<>();
        List<Map<String, Object>> invites = new ArrayList<>();
        List<Map<String, Object>> claims = new ArrayList<>();
        Set<String> verifiedNow = new HashSet<>();
        Set<List<String>> warsNow = new HashSet<>();
        Map<String, Clan> knownClans = new LinkedHashMap<>();
        for (Clan clan : simpleClans.getClanManager().getClans()) {
            knownClans.put(id(clan), clan);
        }
        for (ClanPlayer player : simpleClans.getClanManager().getAllClanPlayers()) {
            if (player.getUniqueId() == null || player.getName() == null || player.getName().isEmpty()) {
                continue;
            }
            Clan clan = player.getClan();
            String clanId = clan != null && knownClans.containsKey(id(clan)) ? id(clan) : null;
            players.put(player.getUniqueId(), object(
                    "uuid", player.getUniqueId().toString(), "name", player.getName(),
                    "clanExternalId", clanId, "role", clanId == null ? null : player.isLeader() ? "leader" : "member",
                    "verified", clanId != null && player.isTrusted(),
                    "joinedClanAt", clanId == null ? null : timestamp(player.getJoinDate()),
                    "rank", clanId == null ? null : player.getRankDisplayName(),
                    "lastSeenAt", timestamp(player.getLastSeen()), "pastClanExternalIds", new ArrayList<>(player.getPastClans()),
                    "kills", player.getTotalKills(), "deaths", player.getDeaths(), "kdr", finite(player.getKDR()),
                    "killsByType", object("ally", player.getAllyKills(), "rival", player.getRivalKills(),
                            "civilian", player.getCivilianKills(), "neutral", player.getNeutralKills())));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Map<String, Object> stats = players.get(uuid);
            if (player.hasMetadata("NPC") || player.getMetadata("vanished").stream().anyMatch(value -> value.asBoolean())) {
                continue;
            }
            if (stats == null) {
                stats = object("uuid", uuid.toString(), "name", player.getName());
                players.put(uuid, stats);
            } else {
                stats.put("name", player.getName());
            }
            online.add(object("uuid", uuid.toString(), "name", player.getName(), "clanExternalId", stats.get("clanExternalId")));
        }
        for (Clan clan : knownClans.values()) {
            List<String> members = new ArrayList<>();
            for (ClanPlayer member : clan.getMembers()) {
                Map<String, Object> player = players.get(member.getUniqueId());
                if (player != null && id(clan).equals(player.get("clanExternalId"))) {
                    members.add(member.getUniqueId().toString());
                }
            }
            Map<String, Object> clanData = object("externalId", id(clan), "tag", clan.getTag(), "name", clan.getName(),
                    "description", clan.getDescription(),
                    "verified", clan.isVerified(), "permanent", clan.isPermanent(), "memberExternalIds", members,
                    "foundedAt", timestamp(clan.getFounded()), "colorTag", clan.getColorTag(),
                    "kdr", finite(clan.getTotalKDR()), "deaths", clan.getTotalDeaths(), "kills", clan.getTotalKills(),
                    "allyExternalIds", new ArrayList<>(clan.getAllies()), "rivalExternalIds", new ArrayList<>(clan.getRivals()));
            double balance = clan.getBalance();
            if (Double.isFinite(balance)) {
                clanData.put("balance", balance);
            }
            clans.add(clanData);
            if (clan.isVerified()) {
                verifiedNow.add(id(clan));
            }
            for (Clan opponent : clan.getWarringClans()) {
                if (opponent == null || id(clan).equals(id(opponent)) || !knownClans.containsKey(id(opponent))) {
                    continue;
                }
                List<String> pair = Arrays.asList(id(clan), id(opponent));
                pair.sort(String::compareTo);
                if (warsNow.add(pair)) {
                    War war = simpleClans.getProtectionManager().getWar(clan, opponent);
                    wars.add(object("clanExternalIds", pair, "casualties", war == null ? null :
                            object(id(clan), war.getCasualties(clan), id(opponent), war.getCasualties(opponent))));
                }
            }
            if (collectClaims) {
                collectClaims(members, id(clan), claims);
            }
        }
        for (Request invite : pendingInvites) {
            String clanId = id(invite.getClan());
            if (knownClans.containsKey(clanId)) {
                invites.add(object("clanExternalId", clanId, "playerName", invite.getTarget(),
                        "inviterUuid", invite.getRequester().getUniqueId()));
            }
        }
        recordChanges(knownClans.keySet(), verifiedNow, warsNow);
        events.addAll(feed.batch());
        Map<String, Object> dashboard = object("onlinePlayers", online, "wars", wars,
                "pendingInvites", invites, "events", events);
        if (collectClaims) {
            dashboard.put("claims", claims);
        }
        Map<String, Object> result = object("takenAt", takenAt, "clans", clans, "players", new ArrayList<>(players.values()),
                "extensions", object("scCollectorV1", dashboard));
        if (!serverId.isEmpty()) {
            result.put("serverId", serverId);
        }
        return result;
    }

    private void collectClaims(List<String> members, String clanId, List<Map<String, Object>> claims) {
        for (World world : Bukkit.getWorlds()) {
            Set<String> ids = new HashSet<>();
            for (String member : members) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(member));
                for (Land land : simpleClans.getProtectionManager().getLandsOf(player, world)) {
                    if (ids.add(land.getId())) {
                        claims.add(object("id", land.getId(), "worldUuid", world.getUID().toString(), "clanExternalId", clanId));
                    }
                }
            }
        }
    }

    private void recordChanges(Set<String> clans, Set<String> verifiedNow, Set<List<String>> warsNow) {
        if (verified != null) {
            for (String id : clans) {
                if (verified.contains(id) != verifiedNow.contains(id)) {
                    feed.record(verifiedNow.contains(id) ? "clan.verified" : "clan.unverified", "clanExternalId", id);
                }
            }
            for (List<String> pair : warsNow) {
                if (!activeWars.contains(pair)) {
                    feed.record("war.started", "clanExternalIds", pair);
                }
            }
            for (List<String> pair : activeWars) {
                if (!warsNow.contains(pair)) {
                    feed.record("war.ended", "clanExternalIds", pair);
                }
            }
        }
        verified = verifiedNow;
        activeWars = warsNow;
    }

    static String id(Clan clan) {
        return clan.getTag();
    }

    static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static String timestamp(long millis) {
        return millis > 0 ? Instant.ofEpochMilli(millis).toString() : null;
    }

    static Map<String, Object> object(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            result.put((String) fields[i], fields[i + 1]);
        }
        return result;
    }
}
