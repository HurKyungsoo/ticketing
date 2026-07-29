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
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * 리다이렉트를 직접 따라간다. culture.go.kr 이 http 요청을 https 로 302 시키는데
 * HttpURLConnection 은 프로토콜이 바뀌는 리다이렉트를 자동으로 따라가지 않아서,
 * 리다이렉트 HTML 본문을 이미지로 읽으려다 실패하고 포스터가 통째로 안 떴다.
 *
 * 보안: 외부에서 준 URL 로 서버가 직접 요청을 보내므로, 호스트를 허용 목록으로 막지 않으면
 * 내부망이나 클라우드 메타데이터(169.254.169.254) 를 대신 긁어오는 SSRF 통로가 된다.
 * 그래서 공공데이터 이미지 호스트만 허용하고, 리다이렉트 대상도 매 홉마다 다시 검사한다.
 */
@Slf4j
@RestController
public class PosterProxyController {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "www.kopis.or.kr", "kopis.or.kr",
            "www.culture.go.kr", "culture.go.kr");

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; ticketing/1.0)";

    private static final int MAX_WIDTH = 500;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_REDIRECTS = 3;

    /**
     * 썸네일 1장이 60~70KB 라 공연 전체(수백 건)를 다 담으면 50MB 를 넘긴다.
     * 건수가 아니라 바이트로 상한을 두고 LRU 로 밀어낸다 — 건수 상한 + 초과 시 전체 삭제
     * 방식은 목록을 넓게 둘러보는 순간 캐시가 통째로 날아가서 매번 원본을 다시 받게 된다.
     */
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;

    /**
     * 실패한 URL 을 잠시 기억해둔다. 원본이 지워진 포스터는 계속 실패하는데, 기억하지 않으면
     * 목록을 열 때마다 죽은 URL 로 매번 외부 요청이 나간다(연결 3초 + 읽기 5초까지 대기).
     * 영구 차단은 아니고, 원본이 복구되면 다시 시도할 수 있도록 짧게만 잡는다.
     */
    private static final Duration FAILURE_TTL = Duration.ofMinutes(10);
    private static final int MAX_FAILURE_ENTRIES = 2_000;

    /** accessOrder=true 라 순회 순서가 "가장 오래 안 쓴 것부터"가 된다. 접근이 구조를 바꾸므로 조회도 동기화한다. */
    private final Map<String, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedBytes;

    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    @GetMapping("/img/poster")
    public ResponseEntity<byte[]> poster(@RequestParam("u") String url) {
        if (!isAllowed(url)) {
            return ResponseEntity.badRequest().build();
        }

        byte[] cached = lookup(url);
        if (cached != null) {
            return imageResponse(cached);
        }
        if (recentlyFailed(url)) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] thumbnail = fetchAndResize(url);
            if (thumbnail == null) {
                rememberFailure(url);
                return ResponseEntity.notFound().build();
            }
            store(url, thumbnail);
            return imageResponse(thumbnail);
        } catch (Exception e) {
            log.debug("포스터 프록시 실패. url={}, msg={}", url, e.getMessage());
            rememberFailure(url);
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

    private synchronized byte[] lookup(String url) {
        return cache.get(url);
    }

    /** 상한을 넘으면 가장 오래 안 쓴 것부터 밀어낸다. 방금 넣은 항목은 대상에서 제외한다. */
    private synchronized void store(String url, byte[] thumbnail) {
        byte[] previous = cache.put(url, thumbnail);
        cachedBytes += thumbnail.length - (previous == null ? 0 : previous.length);

        Iterator<Map.Entry<String, byte[]>> it = cache.entrySet().iterator();
        while (cachedBytes > MAX_CACHE_BYTES && it.hasNext()) {
            Map.Entry<String, byte[]> eldest = it.next();
            if (eldest.getKey().equals(url)) continue;
            cachedBytes -= eldest.getValue().length;
            it.remove();
        }
    }

    private boolean recentlyFailed(String url) {
        Long at = failedAt.get(url);
        if (at == null) return false;
        if (System.currentTimeMillis() - at < FAILURE_TTL.toMillis()) return true;

        failedAt.remove(url, at);
        return false;
    }

    private void rememberFailure(String url) {
        // 죽은 URL 이 계속 늘어나도 무한정 쌓이지 않게, 만료된 것부터 정리한다.
        if (failedAt.size() >= MAX_FAILURE_ENTRIES) {
            long expiry = System.currentTimeMillis() - FAILURE_TTL.toMillis();
            failedAt.values().removeIf(at -> at < expiry);
        }
        failedAt.put(url, System.currentTimeMillis());
    }

    /** 원본을 받아 폭 기준으로 줄이고 JPEG 으로 다시 인코딩한다. 애니메이션 GIF 는 첫 프레임만 쓴다. */
    private byte[] fetchAndResize(String url) throws Exception {
        BufferedImage source = readImage(url);
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

    /**
     * 리다이렉트를 직접 따라가며 이미지를 읽는다. 자동 추종을 꺼두는 이유는 두 가지다.
     * (1) HttpURLConnection 은 http→https 를 어차피 안 따라간다.
     * (2) 매 홉의 대상 호스트를 허용 목록으로 다시 검사해야 한다.
     */
    private BufferedImage readImage(String startUrl) throws Exception {
        String url = startUrl;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            // 일부 공공기관 서버가 UA 없는 요청을 막아서 최소한의 UA 를 넣는다.
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int status = connection.getResponseCode();
            if (!isRedirect(status)) {
                if (status != HttpURLConnection.HTTP_OK) {
                    log.debug("포스터 원본이 200 이 아님. status={}, url={}", status, url);
                    return null;
                }
                try (InputStream in = connection.getInputStream()) {
                    return ImageIO.read(in);
                }
            }

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) return null;

            // 상대 경로 Location 도 있으므로 현재 URL 기준으로 해석한다.
            url = URI.create(url).resolve(location).toString();
            if (!isAllowed(url)) {
                log.debug("허용 목록에 없는 리다이렉트 대상. url={}", url);
                return null;
            }
        }

        log.debug("리다이렉트가 너무 많음. url={}", startUrl);
        return null;
    }

    private boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    private ResponseEntity<byte[]> imageResponse(byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(body);
    }
}
