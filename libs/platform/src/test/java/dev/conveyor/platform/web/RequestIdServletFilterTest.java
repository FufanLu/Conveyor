package dev.conveyor.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdServletFilterTest {

    private final RequestIdServletFilter filter = new RequestIdServletFilter();

    @Test
    void exposesIdToHandlerThroughMdcAndAttributeThenClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/products/B0TEST");
        request.addHeader(RequestIds.HEADER, "caller-id-0002");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInHandler = new AtomicReference<>();
        AtomicReference<Object> attributeInHandler = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                mdcInHandler.set(MDC.get(RequestIds.MDC_KEY));
                attributeInHandler.set(req.getAttribute(RequestIds.ATTRIBUTE));
            }
        }));

        assertThat(mdcInHandler.get()).isEqualTo("caller-id-0002");
        assertThat(attributeInHandler.get()).isEqualTo("caller-id-0002");
        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("caller-id-0002");
        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenHandlerThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        MockFilterChain failing = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                throw new IllegalStateException("boom");
            }
        });

        try {
            filter.doFilter(request, new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            // the exception itself is the servlet container's business
        }

        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
    }
}
