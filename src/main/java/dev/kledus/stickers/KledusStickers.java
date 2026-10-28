package dev.kledus.stickers;

import com.sun.net.httpserver.HttpServer;
import dev.kledus.stickers.EmoteLoader.Emote;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class KledusStickers extends JavaPlugin implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("kledus-stickers-pack".getBytes());
    private static final Key ATLAS = Key.key("minecraft", "gui");

    private volatile Map<String, Emote> emotes = Map.of();
    private volatile byte[] packBytes;
    private volatile byte[] packHash;
    private volatile String packHashHex = "";
    private HttpServer server;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startServer();
        reloadEmotes(null);
    }

    @Override
    public void onDisable() {
        if (server != null) server.stop(0);
    }

    // ---------------- загрузка и пак ----------------

    private void reloadEmotes(CommandSender who) {
        reloadConfig();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                EmoteLoader.Result r = new EmoteLoader(this).build();
                byte[] hash = MessageDigest.getInstance("SHA-1").digest(r.zip());
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) hex.append(String.format("%02x", b));

                emotes = r.emotes();
                packBytes = r.zip();
                packHash = hash;
                packHashHex = hex.toString();

                String msg = "Загружено стикеров: " + r.emotes().size();
                getLogger().info(msg);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (who != null) who.sendMessage(Component.text(msg, NamedTextColor.GREEN));
                    Bukkit.getOnlinePlayers().forEach(this::sendPack);
                });
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "Не удалось загрузить стикеры", ex);
                if (who != null) {
                    Bukkit.getScheduler().runTask(this, () ->
                            who.sendMessage(Component.text("Ошибка: " + ex.getMessage(), NamedTextColor.RED)));
                }
            }
        });
    }

    private void startServer() {
        int port = getConfig().getInt("pack.port", 8765);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/pack.zip", ex -> {
                byte[] data = packBytes;
                if (data == null) {
                    ex.sendResponseHeaders(503, -1);
                } else {
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, data.length);
                    ex.getResponseBody().write(data);
                }
                ex.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            getLogger().info("HTTP-сервер ресурспака запущен на порту " + port);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Не удалось открыть порт " + port + " для ресурспака", e);
        }
    }

    private void sendPack(Player p) {
        if (packBytes == null) return;
        String host = getConfig().getString("pack.host", "");
        if (host.isBlank() || host.equals("YOUR.SERVER.IP")) {
            getLogger().warning("Укажи pack.host в config.yml (публичный IP/домен сервера)!");
            return;
        }
        String url = "http://" + host + ":" + getConfig().getInt("pack.port", 8765) + "/pack.zip?h=" + packHashHex;
        p.addResourcePack(PACK_ID, url, packHash,
                Component.text(getConfig().getString("pack.prompt", "Stickers")),
                getConfig().getBoolean("pack.force", false));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        sendPack(e.getPlayer());
    }

    // ---------------- чат ----------------

    private Component spriteOf(Emote e) {
        return Component.object(ObjectContents.sprite(ATLAS, Key.key(EmoteLoader.NAMESPACE, e.sprite())))
                .hoverEvent(HoverEvent.showText(Component.text(":" + e.name() + ":", NamedTextColor.GRAY)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!event.getPlayer().hasPermission("kledusstickers.use")) return;
        Map<String, Emote> map = emotes;
        if (map.isEmpty()) return;

        boolean colons = getConfig().getBoolean("require-colons", true);
        Pattern pattern = colons ? Pattern.compile(":([A-Za-z0-9_]+):") : Pattern.compile("\\b([A-Za-z0-9_]+)\\b");

        TextReplacementConfig cfg = TextReplacementConfig.builder()
                .match(pattern)
                .replacement((m, b) -> {
                    Emote em = map.get(m.group(1).toLowerCase());
                    return em == null ? b : spriteOf(em);
                })
                .build();
        event.message(event.message().replaceText(cfg));
    }

    // ---------------- команды ----------------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                if (!s.hasPermission("kledusstickers.admin")) {
                    s.sendMessage(Component.text("Нет прав", NamedTextColor.RED));
                    return true;
                }
                s.sendMessage(Component.text("Перезагружаю стикеры...", NamedTextColor.YELLOW));
                reloadEmotes(s);
            }
            case "pack" -> {
                if (s instanceof Player p) sendPack(p);
            }
            default -> {
                if (emotes.isEmpty()) {
                    s.sendMessage(Component.text("Стикеры ещё не загружены", NamedTextColor.RED));
                    return true;
                }
                Component line = Component.text("Стикеры (пиши :имя:): ", NamedTextColor.GOLD);
                for (Emote e : emotes.values()) {
                    line = line.append(spriteOf(e)).append(Component.space());
                }
                s.sendMessage(line);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 ? List.of("list", "reload", "pack") : List.of();
    }
}
