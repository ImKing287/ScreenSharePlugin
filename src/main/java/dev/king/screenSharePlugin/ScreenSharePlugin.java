package dev.king.screenSharePlugin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.yaml.snakeyaml.Yaml;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Plugin(id = "screenshareplugin", name = "ScreenSharePlugin", version = "1.0")
public class ScreenSharePlugin {

    private final ProxyServer server;
    private final Path dataFolder;

    private final HashMap<UUID, UUID> activeScreenshare = new HashMap<>();
    private final HashMap<UUID, Long> screenshareStart = new HashMap<>();
    private final HashMap<UUID, RegisteredServer> originalServer = new HashMap<>();

    private Map<String, Object> config;

    @Inject
    public ScreenSharePlugin(ProxyServer server, @DataDirectory Path dataFolder) {
        this.server = server;
        this.dataFolder = dataFolder;

        loadConfig();


        server.getCommandManager().register("ss", new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                CommandSource source = invocation.source();

                if (!(source instanceof Player)) {
                    source.sendMessage(Component.text(getMessage("only-players"), NamedTextColor.RED));
                    return;
                }

                Player executor = (Player) source;
                String[] args = invocation.arguments();

                if (args.length != 1) {
                    executor.sendMessage(Component.text(getMessage("usage-ss"), NamedTextColor.YELLOW));
                    return;
                }

                Player target = server.getPlayer(args[0]).orElse(null);
                if (target == null) {
                    executor.sendMessage(Component.text(getMessage("player-not-found"), NamedTextColor.RED));
                    return;
                }

                String ssServerName = (String) config.getOrDefault("screenshare-server", "screenshare");
                RegisteredServer screenServer = server.getServer(ssServerName).orElse(null);
                if (screenServer == null) {
                    executor.sendMessage(Component.text(getMessage("server-not-found"), NamedTextColor.RED));
                    return;
                }

                target.getCurrentServer().ifPresent(conn -> originalServer.put(target.getUniqueId(), conn.getServer()));

                executor.createConnectionRequest(screenServer).connect();
                target.createConnectionRequest(screenServer).connect();

                activeScreenshare.put(executor.getUniqueId(), target.getUniqueId());
                screenshareStart.put(executor.getUniqueId(), System.currentTimeMillis());

                executor.sendMessage(Component.text(getMessage("ss-start")
                        .replace("{target}", target.getUsername()), NamedTextColor.GREEN));
                target.sendMessage(Component.text(getMessage("ss-notify")
                        .replace("{executor}", executor.getUsername()), NamedTextColor.YELLOW));
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                String[] args = invocation.arguments();

                if (args.length == 1) {
                    String prefix = args[0].toLowerCase();
                    return server.getAllPlayers().stream()
                            .map(Player::getUsername)
                            .filter(name -> name.toLowerCase().startsWith(prefix))
                            .toList();
                }

                return List.of();
            }
        });


        server.getCommandManager().register("sstime", new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                CommandSource source = invocation.source();
                if (!(source instanceof Player)) return;
                Player executor = (Player) source;

                if (!screenshareStart.containsKey(executor.getUniqueId())) {
                    executor.sendMessage(Component.text(getMessage("not-in-ss"), NamedTextColor.RED));
                    return;
                }

                long startTime = screenshareStart.get(executor.getUniqueId());
                long seconds = (System.currentTimeMillis() - startTime) / 1000;
                executor.sendMessage(Component.text(getMessage("ss-time")
                        .replace("{seconds}", String.valueOf(seconds)), NamedTextColor.GREEN));
            }
        });


        server.getCommandManager().register("ssreload", new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                loadConfig();
                invocation.source().sendMessage(Component.text("Config ricaricata!", NamedTextColor.GREEN));
            }
        });
    }

    private void loadConfig() {
        try {
            if (!Files.exists(dataFolder)) {
                Files.createDirectories(dataFolder);
            }
            Path configFile = dataFolder.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    } else {
                        String defaultConfig = """
                                screenshare-server: "screenshare"
                                messages:
                                  only-players: "Solo i giocatori possono eseguire questo comando!"
                                  usage-ss: "Uso corretto: /ss <giocatore>"
                                  player-not-found: "Giocatore non trovato!"
                                  server-not-found: "Server screenshare non trovato!"
                                  ss-start: "Hai iniziato lo screenshare con {target}"
                                  ss-notify: "Sei stato portato in screenshare da {executor}"
                                  not-in-ss: "Non stai facendo screenshare."
                                  ss-time: "Screenshare attivo da {seconds} secondi."
                                  staff-left: "Screenshare terminato perché lo staff ha lasciato lo screenshare."
                                  player-left: "Screenshare terminato perché il giocatore è uscito."
                                """;
                        Files.writeString(configFile, defaultConfig);
                    }
                }
            }
            try (InputStream in = Files.newInputStream(configFile)) {
                Yaml yaml = new Yaml();
                config = yaml.load(in);
            }
        } catch (IOException e) {
            e.printStackTrace();
            config = new HashMap<>();
        }
    }

    private String getMessage(String key) {
        Map<String, String> messages = (Map<String, String>) config.get("messages");
        if (messages != null && messages.containsKey(key)) {
            return messages.get(key);
        }
        return key;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player quitting = event.getPlayer();
        handleStaffExitOrSwitch(quitting);
        handleTargetExit(quitting);
    }

    @Subscribe
    public void onStaffSwitch(ServerConnectedEvent event) {
        Player staff = event.getPlayer();
        UUID staffId = staff.getUniqueId();

        if (!activeScreenshare.containsKey(staffId)) {
            return; 
        }


        String ssServerName = (String) config.getOrDefault("screenshare-server", "screenshare");
        String targetServer = event.getServer().getServerInfo().getName();


        if (!targetServer.equalsIgnoreCase(ssServerName)) {
            handleStaffExitOrSwitch(staff);
        }
    }

    private void handleStaffExitOrSwitch(Player staff) {
        UUID staffId = staff.getUniqueId();
        if (activeScreenshare.containsKey(staffId)) {
            UUID targetId = activeScreenshare.get(staffId);
            Player target = server.getPlayer(targetId).orElse(null);
            if (target != null) {
                target.sendMessage(Component.text(getMessage("staff-left"), NamedTextColor.RED));
                RegisteredServer origin = originalServer.get(targetId);
                if (origin != null) {
                    target.createConnectionRequest(origin).connect();
                }
                originalServer.remove(targetId);
            }
            activeScreenshare.remove(staffId);
            screenshareStart.remove(staffId);
        }
    }

    private void handleTargetExit(Player target) {
        UUID targetId = target.getUniqueId();
        if (activeScreenshare.containsValue(targetId)) {
            UUID executorId = null;
            for (UUID key : activeScreenshare.keySet()) {
                if (activeScreenshare.get(key).equals(targetId)) {
                    executorId = key;
                    break;
                }
            }
            if (executorId != null) {
                activeScreenshare.remove(executorId);
                screenshareStart.remove(executorId);

                Player executor = server.getPlayer(executorId).orElse(null);
                if (executor != null) {
                    executor.sendMessage(Component.text(getMessage("player-left"), NamedTextColor.RED));
                }
            }
        }
    }
}

