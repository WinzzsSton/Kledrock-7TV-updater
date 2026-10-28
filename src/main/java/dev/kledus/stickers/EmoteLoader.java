package dev.kledus.stickers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Скачивает эмоуты из 7TV и собирает из них ресурспак со шрифтами (BitMap Font). */
public final class EmoteLoader {

    public static final String NAMESPACE = "kledus";

    public record Emote(String name, String sprite, boolean animated, String charCode) {}

    public record Result(byte[] zip, Map<String, Emote> emotes) {}

    private record Decoded(List<BufferedImage> frames, List<Integer> delaysMs) {}

    private final KledusStickers plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public EmoteLoader(KledusStickers plugin) {
        this.plugin = plugin;
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "KledusStickers/1.0")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode() + " " + url);
        return res.body();
    }

    public Result build() throws Exception {
        FileConfiguration cfg = plugin.getConfig();
        // Размер исходной картинки (чтобы не сломать память)
        int size = Math.max(8, cfg.getInt("sprite-size", 32));
        int maxFrames = Math.max(1, cfg.getInt("max-frames", 60));

        // НАСТРОЙКИ РАЗМЕРА В ЧАТЕ (регулируйте здесь):
        int fontHeight = 64; // Высота стикера (чем больше, тем крупнее в чате)
        int fontAscent = 48; // Подъем (должен быть меньше height)

        String twitchId = cfg.getString("twitch-id", "").trim();
        if (twitchId.isEmpty()) {
            String login = cfg.getString("twitch-login", "KledusOfficial").trim();
            twitchId = new String(get("https://decapi.me/twitch/id/" + login), StandardCharsets.UTF_8).trim();
            if (!twitchId.matches("\\d+")) {
                throw new IOException("Не удалось получить Twitch ID для " + login + " (ответ: " + twitchId + ").");
            }
        }

        JsonObject user = JsonParser.parseString(
                new String(get("https://7tv.io/v3/users/twitch/" + twitchId), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonElement setEl = user.get("emote_set");
        if (setEl == null || setEl.isJsonNull()) throw new IOException("У пользователя нет активного emote set в 7TV");
        JsonArray list = setEl.getAsJsonObject().getAsJsonArray("emotes");

        Path cacheDir = plugin.getDataFolder().toPath().resolve("cache");
        Files.createDirectories(cacheDir);

        Map<String, Emote> emotes = new LinkedHashMap<>();
        Set<String> usedSprites = new HashSet<>();
        JsonArray fontProviders = new JsonArray();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            putEntry(zip, "pack.mcmeta", ("{\"pack\":{\"pack_format\":75,\"description\":\"KledusStickers 7TV\"}}").getBytes(StandardCharsets.UTF_8));

            int charIndex = 0;
            for (JsonElement el : list) {
                JsonObject e = el.getAsJsonObject();
                String id = e.get("id").getAsString();
                String name = e.get("name").getAsString();
                boolean animatedFlag = e.has("data") && e.getAsJsonObject("data").has("animated")
                        && e.getAsJsonObject("data").get("animated").getAsBoolean();

                Decoded dec = fetchAndDecode(cacheDir, id, animatedFlag);
                if (dec == null) continue;

                String sprite = name.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
                String base = sprite;
                int n = 2;
                while (!usedSprites.add(sprite)) sprite = base + "_" + n++;

                List<BufferedImage> frames = limit(dec, maxFrames);
                List<Integer> delays = limitDelays(dec, maxFrames);
                boolean animated = frames.size() > 1;

                BufferedImage out = new BufferedImage(size, size * frames.size(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = out.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                for (int i = 0; i < frames.size(); i++) {
                    BufferedImage f = frames.get(i);
                    double scale = Math.min((double) size / f.getWidth(), (double) size / f.getHeight());
                    int w = Math.max(1, (int) Math.round(f.getWidth() * scale));
                    int h = Math.max(1, (int) Math.round(f.getHeight() * scale));
                    g.drawImage(f, (size - w) / 2, i * size + (size - h) / 2, w, h, null);
                }
                g.dispose();

                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(out, "png", png);
                String path = "assets/" + NAMESPACE + "/textures/gui/sprites/" + sprite + ".png";
                putEntry(zip, path, png.toByteArray());

                if (animated) {
                    StringBuilder sb = new StringBuilder("{\"animation\":{\"width\":" + size + ",\"height\":" + size + ",\"frames\":[");
                    for (int i = 0; i < frames.size(); i++) {
                        int ticks = Math.max(1, (int) Math.round(delays.get(i) / 50.0));
                        if (i > 0) sb.append(',');
                        sb.append("{\"index\":").append(i).append(",\"time\":").append(ticks).append('}');
                    }
                    sb.append("]}}");
                    putEntry(zip, path + ".mcmeta", sb.toString().getBytes(StandardCharsets.UTF_8));
                }

                String charCode = String.format("\\u%04X", 0xE000 + charIndex++);

                JsonObject provider = new JsonObject();
                provider.addProperty("type", "bitmap");
                provider.addProperty("file", NAMESPACE + ":textures/gui/sprites/" + sprite + ".png");
                provider.addProperty("ascent", fontAscent); 
                provider.addProperty("height", fontHeight); 
                JsonArray chars = new JsonArray();
                chars.add(charCode);
                provider.add("chars", chars);
                fontProviders.add(provider);

                emotes.put(name.toLowerCase(), new Emote(name, sprite, animated, charCode));
            }

            // Сохраняем наш собственный файл шрифта, чтобы не ломать default.json
            JsonObject root = new JsonObject();
            root.add("providers", fontProviders);
            putEntry(zip, "assets/" + NAMESPACE + "/font/stickers.json", root.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new Result(bos.toByteArray(), emotes);
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private Decoded fetchAndDecode(Path cacheDir, String id, boolean animated) {
        String[] candidates = animated ? new String[]{"2x.gif", "3x.gif", "1x.gif"} : new String[]{"2x.png", "3x.png", "1x.png"};
        for (String file : candidates) {
            try {
                Path cached = cacheDir.resolve(id + "_" + file);
                byte[] bytes = Files.exists(cached) ? Files.readAllBytes(cached) : get("https://cdn.7tv.app/emote/" + id + "/" + file);
                if (!Files.exists(cached)) Files.write(cached, bytes);
                Decoded d = decode(bytes);
                if (d != null && !d.frames().isEmpty()) return d;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Decoded decode(byte[] data) throws IOException {
        boolean gif = data.length > 4 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
        if (gif) return decodeGif(data);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        return img == null ? null : new Decoded(List.of(img), List.of(100));
    }

    private Decoded decodeGif(byte[] data) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, false);
            int count = reader.getNumImages(true);
            int w = 0, h = 0;
            IIOMetadata sm = reader.getStreamMetadata();
            if (sm != null) {
                Node root = sm.getAsTree("javax_imageio_gif_stream_1.0");
                IIOMetadataNode lsd = child((IIOMetadataNode) root, "LogicalScreenDescriptor");
                if (lsd != null) {
                    w = Integer.parseInt(lsd.getAttribute("logicalScreenWidth"));
                    h = Integer.parseInt(lsd.getAttribute("logicalScreenHeight"));
                }
            }
            if (w <= 0 || h <= 0) { BufferedImage f = reader.read(0); w = f.getWidth(); h = f.getHeight(); }
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                BufferedImage fr = reader.read(i);
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode desc = child(root, "ImageDescriptor");
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                int x = desc == null ? 0 : Integer.parseInt(desc.getAttribute("imageLeftPosition"));
                int y = desc == null ? 0 : Integer.parseInt(desc.getAttribute("imageTopPosition"));
                int delay = gce == null ? 100 : Integer.parseInt(gce.getAttribute("delayTime")) * 10;
                if (delay <= 10) delay = 100;
                String disposal = gce == null ? "none" : gce.getAttribute("disposalMethod");
                BufferedImage backup = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
                Graphics2D g = canvas.createGraphics();
                g.drawImage(fr, x, y, null);
                g.dispose();
                frames.add(copy(canvas));
                delays.add(delay);
                if (disposal.equals("restoreToBackgroundColor")) {
                    Graphics2D cg = canvas.createGraphics();
                    cg.setComposite(AlphaComposite.Clear);
                    cg.fillRect(x, y, fr.getWidth(), fr.getHeight());
                    cg.dispose();
                } else if (backup != null) { canvas = backup; }
            }
            return new Decoded(frames, delays);
        } finally { reader.dispose(); }
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        var list = parent.getElementsByTagName(name);
        return list.getLength() == 0 ? null : (IIOMetadataNode) list.item(0);
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics(); g.drawImage(src, 0, 0, null); g.dispose();
        return c;
    }

    private static List<BufferedImage> limit(Decoded d, int max) {
        int n = d.frames().size(); if (n <= max) return d.frames();
        int step = (int) Math.ceil(n / (double) max); List<BufferedImage> out = new ArrayList<>();
        for (int i = 0; i < n; i += step) out.add(d.frames().get(i));
        return out;
    }

    private static List<Integer> limitDelays(Decoded d, int max) {
        int n = d.frames().size(); if (n <= max) return d.delaysMs();
        int step = (int) Math.ceil(n / (double) max); List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            int sum = 0; for (int j = i; j < Math.min(n, i + step); j++) sum += d.delaysMs().get(j);
            out.add(sum);
        }
        return out;
    }
}