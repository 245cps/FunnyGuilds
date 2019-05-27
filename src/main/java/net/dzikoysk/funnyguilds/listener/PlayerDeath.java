package net.dzikoysk.funnyguilds.listener;

import net.dzikoysk.funnyguilds.FunnyGuilds;
import net.dzikoysk.funnyguilds.basic.user.User;
import net.dzikoysk.funnyguilds.basic.user.UserCache;
import net.dzikoysk.funnyguilds.concurrency.ConcurrencyManager;
import net.dzikoysk.funnyguilds.concurrency.ConcurrencyTask;
import net.dzikoysk.funnyguilds.concurrency.ConcurrencyTaskBuilder;
import net.dzikoysk.funnyguilds.concurrency.requests.database.DatabaseUpdateGuildPointsRequest;
import net.dzikoysk.funnyguilds.concurrency.requests.database.DatabaseUpdateUserPointsRequest;
import net.dzikoysk.funnyguilds.concurrency.requests.dummy.DummyGlobalUpdateUserRequest;
import net.dzikoysk.funnyguilds.concurrency.requests.rank.RankUpdateUserRequest;
import net.dzikoysk.funnyguilds.data.configs.MessageConfiguration;
import net.dzikoysk.funnyguilds.data.configs.PluginConfiguration;
import net.dzikoysk.funnyguilds.data.configs.PluginConfiguration.DataModel;
import net.dzikoysk.funnyguilds.event.FunnyEvent.EventCause;
import net.dzikoysk.funnyguilds.event.SimpleEventHandler;
import net.dzikoysk.funnyguilds.event.rank.PointsChangeEvent;
import net.dzikoysk.funnyguilds.event.rank.RankChangeEvent;
import net.dzikoysk.funnyguilds.hook.PluginHook;
import net.dzikoysk.funnyguilds.hook.WorldGuardHook;
import net.dzikoysk.funnyguilds.util.IntegerRange;
import net.dzikoysk.funnyguilds.util.commons.ChatUtils;
import net.dzikoysk.funnyguilds.util.commons.MapUtil;
import net.dzikoysk.funnyguilds.util.commons.bukkit.MaterialUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

public class PlayerDeath implements Listener {

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        PluginConfiguration config = FunnyGuilds.getInstance().getPluginConfiguration();
        Player playerVictim = event.getEntity();
        Player playerAttacker = event.getEntity().getKiller();

        User victim = User.get(playerVictim);
        UserCache victimCache = victim.getCache();

        victim.getRank().addDeath();

        if (playerAttacker == null) {
            if (! config.considerLastAttackerAsKiller) {
                victimCache.clearDamage();
                return;
            }

            User lastAttacker = victim.getCache().getLastAttacker();

            if (lastAttacker == null || ! lastAttacker.isOnline()) {
                victimCache.clearDamage();
                return;
            }

            Long attackTime = victim.getCache().wasVictimOf(lastAttacker);

            if (attackTime == null || attackTime + config.lastAttackerAsKillerConsiderationTimeout_ > System.currentTimeMillis()) {
                victimCache.clearDamage();
                return;
            }

            playerAttacker = lastAttacker.getPlayer();
        }

        User attacker = User.get(playerAttacker);
        UserCache attackerCache = attacker.getCache();

        if (victim.equals(attacker)) {
            victimCache.clearDamage();
            return;
        }

        if (PluginHook.isPresent(PluginHook.PLUGIN_WORLDGUARD)) {
            if (WorldGuardHook.isInNonPointsRegion(playerVictim.getLocation()) || WorldGuardHook.isInNonPointsRegion(playerAttacker.getLocation())) {
                victimCache.clearDamage();
                return;
            }
        }

        MessageConfiguration messages = FunnyGuilds.getInstance().getMessageConfiguration();
        
        if (config.rankFarmingProtect) {
            Long attackTimestamp = attackerCache.wasAttackerOf(victim);
            Long victimTimestamp = attackerCache.wasVictimOf(attacker);

            if (attackTimestamp != null) {
                if (attackTimestamp + (config.rankFarmingCooldown * 1000) >= System.currentTimeMillis()) {
                    playerVictim.sendMessage(messages.rankLastVictimV);
                    playerAttacker.sendMessage(messages.rankLastVictimA);
                    
                    victimCache.clearDamage();
                    event.setDeathMessage(null);
                    
                    return;
                }
            }
            else if (victimTimestamp != null) {
                if (victimTimestamp + (config.rankFarmingCooldown * 1000) >= System.currentTimeMillis()) {
                    playerVictim.sendMessage(messages.rankLastAttackerV);
                    playerAttacker.sendMessage(messages.rankLastAttackerA);
                    
                    victimCache.clearDamage();
                    event.setDeathMessage(null);
                    
                    return;
                }
            }
        }
        
        if (config.rankIPProtect) {
            String attackerIP = playerAttacker.getAddress().getHostString();

            if (attackerIP != null && attackerIP.equalsIgnoreCase(playerVictim.getAddress().getHostString())) {
                playerVictim.sendMessage(messages.rankIPVictim);
                playerAttacker.sendMessage(messages.rankIPAttacker);

                victimCache.clearDamage();
                event.setDeathMessage(null);
                
                return;
            }
        }

        int[] rankChanges = new int[2];
        int aP = attacker.getRank().getPoints();
        int vP = victim.getRank().getPoints();

        switch (config.rankSystem) {
            case PERCENT:
                Double d = victim.getRank().getPoints() * (config.percentRankChange / 100);
                rankChanges[0] = d.intValue();
                rankChanges[1] = d.intValue();
                break;
            case STATIC:
                rankChanges[0] = config.staticAttackerChange;
                rankChanges[1] = config.staticVictimChange;
                break;
            case ELO:
            default:
                rankChanges = getEloValues(vP, aP);
                break;
        }

        RankChangeEvent attackerEvent = new PointsChangeEvent(EventCause.USER, attacker.getRank(), attacker, rankChanges[0]);
        RankChangeEvent victimEvent = new PointsChangeEvent(EventCause.USER, victim.getRank(), attacker, rankChanges[1]);
        
        List<String> assistEntries = new ArrayList<>();
        List<User> messageReceivers = new ArrayList<>();
        
        if (SimpleEventHandler.handle(attackerEvent) && SimpleEventHandler.handle(victimEvent)) {
            double attackerDamage = victimCache.killedBy(attacker);
            
            if (config.assistEnable && victimCache.isAssisted()) {
                double toShare = attackerEvent.getChange() * (1 - config.assistKillerShare);
                double totalDamage = victimCache.getTotalDamage() + attackerDamage;
                int givenPoints = 0;

                Map<User, Double> damage = MapUtil.sortByValue(victimCache.getDamage());

                int assists = 0;

                for (Entry<User, Double> assist : damage.entrySet()) {
                    double assistFraction = assist.getValue() / totalDamage;
                    int addedPoints = (int) Math.round(assistFraction * toShare);

                    if (addedPoints <= 0) {
                        continue;
                    }

                    if (config.assistsLimit > 0) {
                        if (assists >= config.assistsLimit) {
                            continue;
                        }
                        
                        assists++;
                    }

                    if (!config.broadcastDeathMessage) {
                        messageReceivers.add(assist.getKey());
                    }
                    
                    givenPoints += addedPoints;
                    
                    String assistEntry = StringUtils.replace(messages.rankAssistEntry, "{PLAYER}", assist.getKey().getName());
                    assistEntry = StringUtils.replace(assistEntry, "{+}", Integer.toString(addedPoints));
                    assistEntry = StringUtils.replace(assistEntry, "{SHARE}", ChatUtils.getPercent(assistFraction));
                    assistEntries.add(assistEntry);
                    
                    assist.getKey().getRank().addPoints(addedPoints);
                }
                
                double attackerPoints = attackerEvent.getChange() - toShare + (givenPoints < toShare ? toShare - givenPoints : 0);
                attackerEvent.setChange((int) Math.round(attackerPoints));
            }
            
            attacker.getRank().addKill();
            attacker.getRank().addPoints(attackerEvent.getChange());
            attackerCache.registerVictim(victim);

            victim.getRank().removePoints(victimEvent.getChange());
            victimCache.registerAttacker(attacker);
            victimCache.clearDamage();
            
            if (!config.broadcastDeathMessage) {
                messageReceivers.add(attacker);
                messageReceivers.add(victim);
            }
        }

        ConcurrencyManager concurrencyManager = FunnyGuilds.getInstance().getConcurrencyManager();
        ConcurrencyTaskBuilder taskBuilder = ConcurrencyTask.builder();

        if (config.dataModel == DataModel.MYSQL) {
            if (victim.hasGuild()) {
                taskBuilder.delegate(new DatabaseUpdateGuildPointsRequest(victim.getGuild()));
            }

            if (attacker.hasGuild()) {
                taskBuilder.delegate(new DatabaseUpdateGuildPointsRequest(attacker.getGuild()));
            }

            taskBuilder.delegate(new DatabaseUpdateUserPointsRequest(victim));
            taskBuilder.delegate(new DatabaseUpdateUserPointsRequest(attacker));
        }

        ConcurrencyTask task = taskBuilder
                .delegate(new DummyGlobalUpdateUserRequest(victim))
                .delegate(new DummyGlobalUpdateUserRequest(attacker))
                .delegate(new RankUpdateUserRequest(victim))
                .delegate(new RankUpdateUserRequest(attacker))
                .build();
        concurrencyManager.postTask(task);

        String deathMessage = messages.rankDeathMessage;
        deathMessage = StringUtils.replace(deathMessage, "{ATTACKER}", attacker.getName());
        deathMessage = StringUtils.replace(deathMessage, "{VICTIM}", victim.getName());
        deathMessage = StringUtils.replace(deathMessage, "{+}", Integer.toString(attackerEvent.getChange()));
        deathMessage = StringUtils.replace(deathMessage, "{-}", Integer.toString(victimEvent.getChange()));
        deathMessage = StringUtils.replace(deathMessage, "{POINTS-FORMAT}", IntegerRange.inRange(vP, config.pointsFormat, "POINTS"));
        deathMessage = StringUtils.replace(deathMessage, "{POINTS}", Integer.toString(victim.getRank().getPoints()));
        deathMessage = StringUtils.replace(deathMessage, "{WEAPON}", MaterialUtils.getMaterialName(playerAttacker.getItemInHand().getType()));
        deathMessage = StringUtils.replace(deathMessage, "{REMAINING-HEALTH}", String.format(Locale.US, "%.2f", playerAttacker.getHealth()));
        deathMessage = StringUtils.replace(deathMessage, "{REMAINING-HEARTS}", Integer.toString((int) (playerAttacker.getHealth() / 2)));

        if (victim.hasGuild()) {
            deathMessage = StringUtils.replace(deathMessage, "{VTAG}", StringUtils.replace(config.chatGuild, "{TAG}", victim.getGuild().getTag()));
        }

        if (attacker.hasGuild()) {
            deathMessage = StringUtils.replace(deathMessage, "{ATAG}", StringUtils.replace(config.chatGuild, "{TAG}", attacker.getGuild().getTag()));
        }

        deathMessage = StringUtils.replace(deathMessage, "{VTAG}", "");
        deathMessage = StringUtils.replace(deathMessage, "{ATAG}", "");
        
        if (config.assistEnable && !assistEntries.isEmpty()) {
            deathMessage += "\n" + StringUtils.replace(messages.rankAssistMessage, "{ASSISTS}", String.join(messages.rankAssistDelimiter, assistEntries));    
        }
        
        if (config.broadcastDeathMessage) {
            if (config.ignoreDisabledDeathMessages) {
                for (Player player : event.getEntity().getWorld().getPlayers()) {
                    event.setDeathMessage(null);
                    player.sendMessage(deathMessage);
                }
            }
            else {
                event.setDeathMessage(deathMessage);
            }
        }
        else {
            event.setDeathMessage(null);
            
            for (User fighter : messageReceivers) {
                if (fighter.isOnline()) {
                    fighter.getPlayer().sendMessage(deathMessage);
                }
            }
        }
        
    }

    private int[] getEloValues(int victimPoints, int attackerPoints) {
        PluginConfiguration config = FunnyGuilds.getInstance().getPluginConfiguration();
        int[] rankChanges = new int[2];

        int aC = IntegerRange.inRange(attackerPoints, config.eloConstants, "ELO_CONSTANTS");
        int vC = IntegerRange.inRange(victimPoints, config.eloConstants, "ELO_CONSTANTS");

        rankChanges[0] = (int) Math.round(aC * (1 - (1.0D / (1.0D + Math.pow(config.eloExponent, (victimPoints - attackerPoints) / config.eloDivider)))));
        rankChanges[1] = (int) Math.round(vC * (0 - (1.0D / (1.0D + Math.pow(config.eloExponent, (attackerPoints - victimPoints) / config.eloDivider)))) * - 1);
        
        return rankChanges;
    }
	
}
