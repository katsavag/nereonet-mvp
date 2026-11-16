package com.nereonix.nereonet_mvp.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Text responses are gzipped by the server when the browser accepts it. MockMvc skips the servlet container's
 * compression, so this runs a real server and reads it with java.net.http, which never decompresses by itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CompressionFlowTest {

    private static final String ONE_YEAR = "max-age=31536000";
    private static final Pattern HASHED_ASSET =
            Pattern.compile("(?:href|src)=\"(/(?:css|js|vendor)/[^\"]+-[0-9a-f]{32}\\.(?:css|js))\"");

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void pageAndHashedTextAssetsAreGzippedAndStillCachedForAYear() throws Exception {
        HttpResponse<byte[]> page = get("/login", true);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Encoding")).hasValue("gzip");
        String html = gunzip(page.body());

        List<String> assets = hashedAssets(html);
        assertThat(assets).anyMatch(u -> u.startsWith("/css/app-"))
                .anyMatch(u -> u.startsWith("/vendor/bootstrap/bootstrap.min-"))
                .anyMatch(u -> u.startsWith("/vendor/bootstrap/bootstrap.bundle.min-"));

        for (String asset : assets) {
            HttpResponse<byte[]> plain = get(asset, false);
            HttpResponse<byte[]> gzipped = get(asset, true);
            assertThat(gzipped.statusCode()).as(asset).isEqualTo(200);
            assertThat(plain.headers().firstValue("Content-Encoding")).as(asset).isEmpty();
            if (plain.body().length >= 1024) {
                assertThat(gzipped.headers().firstValue("Content-Encoding")).as(asset).hasValue("gzip");
                assertThat(gunzip(gzipped.body())).as(asset).isEqualTo(new String(plain.body(), "UTF-8"));
                assertThat(gzipped.body().length).as(asset).isLessThan(plain.body().length);
            }
            assertThat(gzipped.headers().firstValue("Cache-Control")).as(asset).hasValueSatisfying(
                    v -> assertThat(v).contains(ONE_YEAR));
            System.out.printf("P65 size %s: %d -> %d bytes%n", asset, plain.body().length, gzipped.body().length);
        }
    }

    @Test
    void imagesAndFontsAreNotGzipped() throws Exception {
        String html = gunzip(get("/login", true).body());
        Matcher m = Pattern.compile("href=\"(/img/brand/favicon-32-[0-9a-f]{32}\\.png)\"").matcher(html);
        assertThat(m.find()).isTrue();
        HttpResponse<byte[]> png = get(m.group(1), true);
        assertThat(png.statusCode()).isEqualTo(200);
        assertThat(png.headers().firstValue("Content-Encoding")).isEmpty();
    }

    private HttpResponse<byte[]> get(String path, boolean acceptGzip) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (acceptGzip) {
            request.header("Accept-Encoding", "gzip");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String gunzip(byte[] body) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(in.readAllBytes(), "UTF-8");
        }
    }

    private static List<String> hashedAssets(String html) {
        Matcher m = HASHED_ASSET.matcher(html);
        List<String> urls = new ArrayList<>();
        while (m.find()) {
            urls.add(m.group(1));
        }
        return urls;
    }
}
