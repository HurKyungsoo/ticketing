package com.portfolio.ticket.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 포스터를 썸네일에 필요한 해상도로만 메모리에 푼다.
 *
 * {@code ImageIO.read} 는 원본 전체를 풀기 때문에 파일이 작아도 메모리를 크게 쓴다.
 * 4961x7016 JPEG 은 파일이 1.2MB 여도 약 100MB 가 필요해서, 320MB 힙에서 몇 장만 겹치면
 * OutOfMemoryError 가 났다. 헤더로 크기를 먼저 읽고 필요한 만큼만 건너뛰며 푼다.
 */
public final class PosterImageDecoder {

    private PosterImageDecoder() {
    }

    /**
     * @param targetWidth 이 폭 아래로는 줄이지 않는다. 다만 maxPixels 가 우선한다.
     * @param maxPixels   푼 결과의 픽셀 수 상한. 폭은 작고 세로로 아주 긴 이미지를 막는다.
     * @return 읽을 수 있는 이미지 형식이 아니면 null
     */
    public static BufferedImage decode(InputStream in, int targetWidth, long maxPixels) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            if (stream == null) return null;

            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;

            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int step = subsamplingStep(reader.getWidth(0), reader.getHeight(0), targetWidth, maxPixels);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int subsamplingStep(int width, int height, int targetWidth, long maxPixels) {
        int forWidth = Math.max(1, width / targetWidth);
        double pixels = (double) width * height;
        int forPixels = (int) Math.ceil(Math.sqrt(pixels / maxPixels));
        return Math.max(forWidth, Math.max(1, forPixels));
    }
}
