package com.portfolio.ticket.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PosterProxyControllerLimitTest {

    private static final String URL = "http://www.kopis.or.kr/upload/pfmPoster/a.jpg";
    private static final String OTHER_URL = "http://www.kopis.or.kr/upload/pfmPoster/b.jpg";

    /** 변환이 시작되면 release 가 열릴 때까지 붙잡고 있는 가짜 컨트롤러. */
    private static final class BlockingController extends PosterProxyController {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        BlockingController() {
            super(1, Duration.ofMillis(50));
        }

        @Override
        byte[] fetchAndResize(String url) throws Exception {
            calls.incrementAndGet();
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new byte[]{1};
        }
    }

    @Test
    void 변환_자리가_없으면_503_을_돌려주고_실패로_기억하지_않는다() throws Exception {
        BlockingController controller = new BlockingController();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<byte[]>> running = executor.submit(() -> controller.poster(URL));
            assertThat(controller.started.await(5, TimeUnit.SECONDS)).isTrue();

            ResponseEntity<byte[]> busy = controller.poster(OTHER_URL);

            assertThat(busy.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(busy.getHeaders().getFirst("Retry-After")).isNotNull();
            assertThat(controller.calls.get()).isEqualTo(1);

            controller.release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);

            // 자리가 났으니 같은 URL 도 404 가 아니라 다시 처리되어야 한다.
            assertThat(controller.poster(OTHER_URL).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            controller.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 이미_만든_포스터는_다시_변환하지_않는다() {
        BlockingController controller = new BlockingController();
        controller.release.countDown();

        controller.poster(URL);
        controller.poster(URL);

        assertThat(controller.calls.get()).isEqualTo(1);
    }
}
