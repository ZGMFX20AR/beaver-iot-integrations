package com.milesight.beaveriot.integrations.edgeaistream;

import com.milesight.beaveriot.integrations.edgeaistream.config.EdgeAiStreamingConfig;
import com.milesight.beaveriot.integrations.edgeaistream.model.StreamSource;
import com.milesight.beaveriot.integrations.edgeaistream.service.StreamSourceService;
import com.milesight.beaveriot.integrations.edgeaistream.util.Constants;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Relays a camera's MJPEG pipeline to the browser.
 * <p>
 * This endpoint is deliberately reachable without authentication, because its only
 * consumer is an {@code <img>} tag and a browser cannot attach a bearer token to one. It
 * is listed in {@code oauth2.ignore-urls}. The consequence is accepted rather than
 * overlooked: anyone who can reach this server can watch any configured camera.
 * <p>
 * What it must never become is a general-purpose proxy. The target host comes only from a
 * stored source record and the pipeline id is constrained to digits, so a caller cannot
 * point it at a host of their choosing - which matters more than usual here, since
 * certificate verification is disabled for these calls.
 *
 * @author cytron
 */
@RestController
@RequestMapping("/" + Constants.INTEGRATION_ID + "/streams")
@Slf4j
public class EdgeAiStreamRelayController {
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    private final Semaphore permits = new Semaphore(EdgeAiStreamingConfig.MAX_CONCURRENT_STREAMS);
    private final AtomicInteger activeStreams = new AtomicInteger();

    @Autowired
    StreamSourceService streamSourceService;

    @Autowired
    @Qualifier("edgeAiStreamHttpClient")
    OkHttpClient httpClient;

    /**
     * Tenant whose camera sources unauthenticated stream requests resolve against.
     * <p>
     * Needed because tenancy is normally derived from the caller's token, and this endpoint
     * has no token. Rather than reading configuration with no tenant set - which would
     * quietly query across or outside tenant scope - the tenant is stated explicitly, and a
     * {@code ?tenantId=} parameter overrides it for a multi-tenant deployment.
     */
    @Value("${edgeai-stream.default-tenant:default}")
    String defaultTenant;

    @GetMapping("/{sourceId}/{pipelineId}")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable("sourceId") String sourceId,
                                                        @PathVariable("pipelineId") String rawPipelineId,
                                                        @RequestParam(value = "tenantId", required = false) String tenantId) {
        // Tolerate the id still wrapped in braces. The path is shown to users as a template
        // ending in {pipelineId}, and the natural way to fill that in is to replace the word
        // and keep the braces - which is exactly what happened the first time it was used.
        // Stripping them is safe: what remains must still be digits, below.
        String pipelineId = rawPipelineId.replaceAll("^\\{(.*)}$", "$1");
        if (!pipelineId.matches("\\d+")) {
            // Logged, because this is otherwise invisible: an <img> cannot report the status
            // it got, so the only trace of a malformed widget URL would be a broken image.
            log.warn("Rejected camera stream request for source '{}': pipeline id '{}' is not a number",
                    sourceId, rawPipelineId);
            return ResponseEntity.badRequest().build();
        }

        // Read from the cache, never the database. A query on this request would hold its
        // JDBC connection until the request ends - and this request ends when the viewer
        // leaves - so the eleventh concurrent viewer would exhaust Hikari's ten-connection
        // pool and take the whole API down with it. See StreamSourceService#cacheByTenant.
        String tenant = tenantId == null || tenantId.isEmpty() ? defaultTenant : tenantId;
        Optional<StreamSourceService.CachedSource> cached =
                streamSourceService.lookupForRelay(tenant, sourceId);
        if (cached.isEmpty()) {
            log.warn("No camera source '{}' known for tenant '{}'", sourceId, tenant);
            return ResponseEntity.notFound().build();
        }
        StreamSource source = cached.get().source();
        Optional<String> apiKey = Optional.ofNullable(cached.get().apiKey());

        if (!permits.tryAcquire()) {
            log.warn("Refusing camera stream '{}': already relaying the maximum of {}",
                    sourceId, EdgeAiStreamingConfig.MAX_CONCURRENT_STREAMS);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "10")
                    .build();
        }

        Response upstream = null;
        try {
            Request.Builder request = new Request.Builder()
                    .url("https://" + source.getHost() + "/api/v1/pipelines/" + pipelineId + "/stream?annotated=1");
            apiKey.ifPresent(key -> request.header("X-API-Key", key));

            // Opened here, on the request thread, rather than inside the streaming body:
            // it means a camera that is unreachable or rejects the key produces a real HTTP
            // error instead of a 200 that yields no bytes.
            upstream = httpClient.newCall(request.build()).execute();
            if (!upstream.isSuccessful() || upstream.body() == null) {
                int code = upstream.code();
                log.warn("Camera source '{}' refused pipeline {}: HTTP {}", source.getName(), pipelineId, code);
                upstream.close();
                permits.release();
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }

            String contentType = Optional.ofNullable(upstream.header("Content-Type"))
                    .orElse("multipart/x-mixed-replace");
            Response responseToRelay = upstream;
            int active = activeStreams.incrementAndGet();
            log.info("Relaying camera '{}' pipeline {} ({} active)", source.getName(), pipelineId, active);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    // Tells nginx to stop buffering this specific response. Without it the
                    // proxy accumulates frames and the picture lags behind reality.
                    .header("X-Accel-Buffering", "no")
                    .body(relay(responseToRelay, source.getName(), pipelineId));
        } catch (IOException e) {
            log.warn("Could not reach camera source '{}': {}", source.getName(), e.getMessage());
            if (upstream != null) {
                upstream.close();
            }
            permits.release();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (RuntimeException e) {
            if (upstream != null) {
                upstream.close();
            }
            permits.release();
            throw e;
        }
    }

    private StreamingResponseBody relay(Response upstream, String sourceName, String pipelineId) {
        return (OutputStream out) -> {
            try (Response response = upstream;
                 InputStream in = response.body().byteStream()) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    // Flushed per chunk so frames reach the viewer as they arrive; letting
                    // them accumulate in the servlet buffer is what makes a live view lag.
                    out.flush();
                }
            } catch (IOException e) {
                // The expected ending: the viewer closed the tab, so the next write fails.
                // Closing the upstream response above is what stops this server pulling
                // ~0.8 MB/s from the camera for a picture nobody is watching any more.
                log.debug("Viewer of camera '{}' pipeline {} disconnected: {}",
                        sourceName, pipelineId, e.getMessage());
            } finally {
                int active = activeStreams.decrementAndGet();
                permits.release();
                log.info("Stopped relaying camera '{}' pipeline {} ({} active)", sourceName, pipelineId, active);
            }
        };
    }
}
