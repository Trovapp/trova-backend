package com.trova.backend.shipandping;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

final class ArrivalValve extends ValveBase {
    static final String RECEIVED = ArrivalValve.class.getName() + ".received";
    static final String CONTAINER = ArrivalValve.class.getName() + ".active";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private static final Pattern SECONDS = Pattern.compile("t=[0-9]{1,10}\\.[0-9]{3}");
    private static final Pattern MILLIS = Pattern.compile("[0-9]{13}");
    private final boolean trustLoopbackProxy;

    ArrivalValve(boolean trustLoopbackProxy) {
        super(true);
        this.trustLoopbackProxy = trustLoopbackProxy;
    }

    @Override public void invoke(Request request, Response response) throws IOException, ServletException {
        if (!request.getRequestURI().startsWith("/api/")) {
            getNext().invoke(request, response);
            return;
        }
        request.setAttribute(CONTAINER, true);
        try {
            String peer = trustLoopbackProxy ? request.getPeerAddr() : null;
            String header = peer != null && LOOPBACK.contains(peer)
                    ? request.getHeader("X-Request-Start") : null;
            long start = arrival(System.currentTimeMillis(), request.getCoyoteRequest().getStartTime(), header);
            if (start > 0) request.setAttribute(RECEIVED, start);
            getNext().invoke(request, response);
        } finally {
            if (request.getAttribute(RequestFilter.ACTIVE) instanceof RequestFilter.Observation observation
                    && !observation.listening() && !request.isAsyncStarted()) {
                Exception error = response.getCoyoteResponse().getErrorException();
                observation.finish(error instanceof IOException ? "aborted" : null, error);
            }
        }
    }

    static long arrival(long now, long tomcat, String trustedHeader) {
        if (trustedHeader != null && trustedHeader.length() <= 16) {
            long proxy = 0;
            if (SECONDS.matcher(trustedHeader).matches()) {
                proxy = Long.parseLong(trustedHeader.substring(2).replace(".", ""));
            } else if (MILLIS.matcher(trustedHeader).matches()) {
                proxy = Long.parseLong(trustedHeader);
            }
            if (proxy > 0 && proxy <= now && now - proxy <= 600000) return proxy;
        }
        return tomcat > 0 && tomcat <= now ? tomcat : 0;
    }
}
