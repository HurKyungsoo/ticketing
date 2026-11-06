package com.portfolio.ticket;

import com.portfolio.ticket.service.PosterImageDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class PosterImageDecoderTest {

    private static final int TARGET_WIDTH = 500;
    private static final long MAX_PIXELS = 4_000_000L;

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "png", "gif"})
    void 큰_이미지는_필요한_해상도로만_푼다(String format) throws IOException {
        BufferedImage decoded = decode(encode(3000, 4000, format));

        assertThat(decoded.getWidth()).isEqualTo(500);
        assertThat(decoded.getHeight()).isBetween(666, 667);
    }

    @Test
    void 운영에서_터지던_크기도_상한_안에서_푼다() throws IOException {
        BufferedImage decoded = decode(encode(4961, 7016, "jpg"));

        assertThat(decoded.getWidth()).isGreaterThanOrEqualTo(TARGET_WIDTH);
        assertThat((long) decoded.getWidth() * decoded.getHeight()).isLessThanOrEqualTo(MAX_PIXELS);
    }

    @Test
    void 세로로_아주_긴_이미지도_픽셀_상한을_넘지_않는다() throws IOException {
        BufferedImage decoded = decode(encode(600, 20000, "jpg"));

        assertThat((long) decoded.getWidth() * decoded.getHeight()).isLessThanOrEqualTo(MAX_PIXELS);
    }

    @Test
    void 작은_이미지는_그대로_푼다() throws IOException {
        BufferedImage decoded = decode(encode(300, 400, "jpg"));

        assertThat(decoded.getWidth()).isEqualTo(300);
        assertThat(decoded.getHeight()).isEqualTo(400);
    }

    @Test
    void 이미지가_아니면_null_을_돌려준다() throws IOException {
        assertThat(decode("<html>not an image</html>".getBytes())).isNull();
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return PosterImageDecoder.decode(new ByteArrayInputStream(bytes), TARGET_WIDTH, MAX_PIXELS);
    }

    private static byte[] encode(int width, int height, String format) throws IOException {
        int type = "gif".equals(format) ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_BYTE_GRAY;
        BufferedImage image = new BufferedImage(width, height, type);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }
}
