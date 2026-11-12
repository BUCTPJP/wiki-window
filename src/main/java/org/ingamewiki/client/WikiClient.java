package org.ingamewiki.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.StreamSupport;

/** MediaWiki access is kept independent of Minecraft screens for future ports. */
public final class WikiClient {
    private static final String API = "https://zh.minecraft.wiki/api.php?format=json&formatversion=2&utf8=1&";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static CompletableFuture<JsonObject> get(String query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + query)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "InGameWiki/0.1 (Minecraft Forge client mod)").GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() != 200) throw new CompletionException(new IllegalStateException("HTTP " + response.statusCode()));
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("error")) throw new CompletionException(new IllegalStateException("Wiki API error"));
            return json;
        });
    }
    public static CompletableFuture<List<String>> search(String query) {
        return get("action=query&list=search&srlimit=10&srsearch=" + encode(query)).thenApply(json ->
            StreamSupport.stream(json.getAsJsonObject("query").getAsJsonArray("search").spliterator(), false)
                .map(item -> item.getAsJsonObject().get("title").getAsString()).toList());
    }
    public static CompletableFuture<String> article(String title) {
        return get("action=query&prop=extracts&explaintext=1&redirects=1&titles=" + encode(title)).thenApply(json -> {
            JsonObject page = json.getAsJsonObject("query").getAsJsonArray("pages").get(0).getAsJsonObject();
            if (!page.has("extract")) throw new CompletionException(new IllegalStateException("Article unavailable"));
            return page.get("extract").getAsString();
        });
    }
    public record Thumbnail(String url, int width, int height) {}
    public static CompletableFuture<Thumbnail> thumbnail(String title) {
        return get("action=query&prop=pageimages&piprop=thumbnail&pithumbsize=320&redirects=1&titles=" + encode(title)).thenApply(json -> {
            JsonObject page = json.getAsJsonObject("query").getAsJsonArray("pages").get(0).getAsJsonObject();
            if (!page.has("thumbnail")) return null;
            JsonObject image = page.getAsJsonObject("thumbnail");
            return new Thumbnail(image.get("source").getAsString(), image.get("width").getAsInt(), image.get("height").getAsInt());
        });
    }
    public static CompletableFuture<byte[]> image(Thumbnail image) {
        URI uri = URI.create(image.url());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null ||
                !(uri.getHost().equals("minecraft.wiki") || uri.getHost().endsWith(".minecraft.wiki")))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Untrusted image host"));
        // Redirects are disabled for image requests so the host check cannot be bypassed.
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "InGameWiki/0.2 (Minecraft Forge client mod)").GET().build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).thenApply(response -> {
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("Image HTTP " + response.statusCode());
                byte[] bytes = in.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IllegalStateException("Image too large");
                return bytes;
            } catch (Exception e) { throw new CompletionException(e); }
        });
    }

}
