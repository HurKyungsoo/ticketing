package com.portfolio.ticket;

import com.portfolio.ticket.external.ExternalPerformance;
import com.portfolio.ticket.external.KopisFetchException;
import com.portfolio.ticket.external.KopisPerformanceClient;
import com.portfolio.ticket.external.PublicDataParser;
import com.portfolio.ticket.external.PublicDataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KopisPerformanceClientTest {

    private static final String LIST_XML = """
            <dbs><db>
              <mt20id>PF1</mt20id><prfnm>테스트 공연</prfnm><genrenm>연극</genrenm>
              <fcltynm>테스트 극장</fcltynm><area>서울</area>
              <prfpdfrom>2099.01.01</prfpdfrom><prfpdto>2099.01.02</prfpdto>
              <poster>http://www.kopis.or.kr/a.jpg</poster>
            </db></dbs>
            """;
    private static final String DETAIL_XML = "<dbs><db><mt20id>PF1</mt20id><prfruntime>50분</prfruntime></db></dbs>";
    private static final String BLOCKED_HTML = "<HTML><BODY><H1>Request Blocked</H1></BODY></HTML>";
    private static final String DETAIL_PATH = "/pblprfr/PF1";

    private MockRestServiceServer server;
    private PublicDataProperties properties;
    private KopisPerformanceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new PublicDataProperties();
        properties.setKopisUrl("http://kopis.test/pblprfr");
        properties.setKopisFacilityUrl("http://kopis.test/prfplc");
        properties.setKopisServiceKey("key");
        properties.setKopisMinCallIntervalMs(0);
        properties.setKopisBlockWaitMs(0);
        client = new KopisPerformanceClient(builder.build(), properties, new PublicDataParser());
    }

    @Test
    void 차단이_풀리면_다시_시도해서_상세를_채운다() {
        expectList();
        server.expect(requestTo(containsString(DETAIL_PATH))).andRespond(blocked());
        server.expect(requestTo(containsString(DETAIL_PATH))).andRespond(xml(DETAIL_XML));

        List<ExternalPerformance> result = client.fetchPage(1, null);

        assertThat(result).singleElement()
                .satisfies(p -> assertThat(p.getRunningTime()).isEqualTo("50분"));
        server.verify();
    }

    @Test
    void 상세_차단이_풀리지_않으면_수집을_중단한다() {
        expectList();
        server.expect(times(4), requestTo(containsString(DETAIL_PATH))).andRespond(blocked());

        assertThatThrownBy(() -> client.fetchPage(1, null)).isInstanceOf(KopisFetchException.class);
        server.verify();
    }

    @Test
    void 차단이_아닌_상세_실패는_그_건만_목록_정보로_넘어간다() {
        expectList();
        server.expect(requestTo(containsString(DETAIL_PATH))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        List<ExternalPerformance> result = client.fetchPage(1, null);

        assertThat(result).singleElement().satisfies(p -> assertThat(p.getRunningTime()).isNull());
    }

    @Test
    void 목록_호출_실패는_빈_목록이_아니라_예외다() {
        server.expect(requestTo(containsString("/pblprfr?"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.fetchPage(1, null)).isInstanceOf(KopisFetchException.class);
    }

    @Test
    void 인증키_오류_응답도_실패로_처리한다() {
        server.expect(requestTo(containsString("/pblprfr?")))
                .andRespond(xml("<dbs><db><returncode>02</returncode><errmsg>SERVICE KEY ERROR</errmsg></db></dbs>"));

        assertThatThrownBy(() -> client.fetchPage(1, null)).isInstanceOf(KopisFetchException.class);
    }

    @Test
    void 결과가_없는_목록은_정상적인_빈_목록이다() {
        server.expect(requestTo(containsString("/pblprfr?"))).andRespond(xml("<dbs></dbs>"));

        assertThat(client.fetchPage(1, null)).isEmpty();
    }

    @Test
    void 호출_사이에_최소_간격을_둔다() {
        properties.setKopisMinCallIntervalMs(200);
        expectList();
        server.expect(requestTo(containsString(DETAIL_PATH))).andRespond(xml(DETAIL_XML));

        long startedAt = System.nanoTime();
        client.fetchPage(1, null);

        assertThat((System.nanoTime() - startedAt) / 1_000_000).isGreaterThanOrEqualTo(190);
    }

    private void expectList() {
        server.expect(requestTo(containsString("/pblprfr?"))).andRespond(xml(LIST_XML));
    }

    private static org.springframework.test.web.client.ResponseCreator blocked() {
        return withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_HTML).body(BLOCKED_HTML);
    }

    private static org.springframework.test.web.client.ResponseCreator xml(String body) {
        return withSuccess(body, MediaType.APPLICATION_XML);
    }
}
