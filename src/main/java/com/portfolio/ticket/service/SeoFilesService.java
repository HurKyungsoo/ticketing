package com.portfolio.ticket.service;

import com.portfolio.ticket.repository.PerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 검색엔진이 읽는 robots.txt 와 sitemap.xml 을 만든다. */
@Service
@RequiredArgsConstructor
public class SeoFilesService {

    /**
     * 좌석도(/schedules/)는 회차마다 좌석을 전부 읽어 무겁고, 필터 조합(/performances?)은 주소가
     * 끝없이 늘어난다. 둘 다 검색 결과에 나올 이유가 없어 크롤러가 쓰지 않게 막는다.
     * 공연 상세는 사이트맵으로 알려 주므로 목록의 필터 주소를 막아도 찾을 수 있다.
     */
    private static final String ROBOTS_RULES = """
            User-agent: *
            Disallow: /schedules/
            Disallow: /performances?
            Disallow: /performances/compare
            Disallow: /api/
            Disallow: /admin/
            Disallow: /mypage/
            Disallow: /login
            Disallow: /signup
            Disallow: /password-reset
            Disallow: /oauth2/
            """;

    private final PerformanceRepository performanceRepository;

    public String robotsTxt(String baseUrl) {
        return ROBOTS_RULES + "\nSitemap: " + baseUrl + "/sitemap.xml\n";
    }

    /** 끝나지 않은 공연만 넣는다. 종료된 공연은 예매할 수 없어 검색으로 데려올 이유가 없다. */
    @Transactional(readOnly = true)
    public String sitemapXml(String baseUrl) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        appendUrl(xml, baseUrl + "/");
        appendUrl(xml, baseUrl + "/performances");
        for (Long id : performanceRepository.findIdsEndingOnOrAfter(LocalDate.now())) {
            appendUrl(xml, baseUrl + "/performances/" + id);
        }
        return xml.append("</urlset>\n").toString();
    }

    private void appendUrl(StringBuilder xml, String location) {
        xml.append("  <url><loc>").append(location).append("</loc></url>\n");
    }
}
