package org.Cry_Legende.sHChatGames.utils;

import org.Cry_Legende.sHLogs.api.SHLogsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Écrit dans SHLogs, ou dans la console sur un serveur sans SHLogs. */
public final class ActionLog {

    private ActionLog() {
    }

    public static void write(Plugin plugin, String file, String message) {
        if (Bukkit.getPluginManager().isPluginEnabled("SHLogs")) {
            SHLogsAPI.log(plugin, file, message);
        } else {
            plugin.getLogger().info(file + " : " + message);
        }
    }
}
