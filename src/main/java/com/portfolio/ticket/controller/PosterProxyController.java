package com.portfolio.ticket.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 포스터 이미지 프록시 + 썸네일 캐시.
 *
 * 원본(KOPIS)은 750x1000 / 400KB 가 넘는데 목록 카드는 250px 남짓으로 줄여서 보여준다.
 * 그대로 핫링크하면 한 페이지에 5MB 넘게 받아야 해서, 캐시가 비어 있으면 포스터가
 * 한참 안 뜬다. 여기서 한 번 받아 폭을 줄여 캐시해두고 그 뒤로는 우리 서버가 응답한다.
 *
 * 부수 효과로 mixed content 문제도 사라진다 — 원본 URL 이 http 라서, 서비스를 https 로
 * 배포하면 브라우저가 이미지를 통째로 차단한다. 프록시를 거치면 같은 오리진이 된다.
 *
 * 보안: 외부에서 준 URL 로 서버가 직접 요청을 보내므로, 호스트를 허용 목록으로 막지 않으면
 * 내부망이나 클라우드 메타데이터(169.254.169.254) 를 대신 긁어오는 SSRF 통로가 된다.
 * 그래서 공공데이터 이미지 호스트만 허용한다.
 */
@Slf4j
@RestController
public class PosterProxyController {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "www.kopis.or.kr", "kopis.or.kr",
            "www.culture.go.kr", "culture.go.kr");

    private static final int MAX_WIDTH = 500;
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    @GetMapping("/img/poster")
    public ResponseEntity<byte[]> poster(@RequestParam("u") String url) {
        if (!isAllowed(url)) {
            return ResponseEntity.badRequest().build();
        }

        byte[] cached = cache.get(url);
        if (cached != null) {
            return imageResponse(cached);
        }

        try {
            byte[] thumbnail = fetchAndResize(url);
            if (thumbnail == null) {
                return ResponseEntity.notFound().build();
            }
            // 무한정 쌓이지 않게 상한을 두고, 넘으면 통째로 비운다.
            // (LRU 를 쓸 만큼 정교할 필요는 없는 규모다)
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(url, thumbnail);
            return imageResponse(thumbnail);
        } catch (Exception e) {
            log.debug("포스터 프록시 실패. url={}, msg={}", url, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private boolean isAllowed(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null
                    && ALLOWED_HOSTS.contains(uri.getHost().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /** 원본을 받아 폭 기준으로 줄이고 JPEG 으로 다시 인코딩한다. 애니메이션 GIF 는 첫 프레임만 쓴다. */
    private byte[] fetchAndResize(String url) throws Exception {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        // 일부 공공기관 서버가 UA 없는 요청을 막아서 최소한의 UA 를 넣는다.
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; ticketing/1.0)");

        BufferedImage source;
        try (InputStream in = connection.getInputStream()) {
            source = ImageIO.read(in);
        }
        if (source == null) return null;

        int width = Math.min(source.getWidth(), MAX_WIDTH);
        int height = (int) Math.round(source.getHeight() * (width / (double) source.getWidth()));

        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(resized, "jpg", out);
        return out.toByteArray();
    }

    private ResponseEntity<byte[]> imageResponse(byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(body);
    }
}
