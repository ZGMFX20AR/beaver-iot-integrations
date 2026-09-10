package com.milesight.beaveriot.integrations.edgeaistream.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Wiring for the video relay: the thread pool its responses are written on, and the HTTP
 * client used to pull from the cameras.
 *
 * @author cytron
 */
@Configuration
public class EdgeAiStreamingConfig implements WebMvcConfigurer {
    /**
     * Ceiling on concurrently relayed streams. Sized against the server, not the cameras:
     * each stream occupies one thread here for as long as a viewer watches.
     */
    public static final int MAX_CONCURRENT_STREAMS = 32;

    private final AsyncTaskExecutor streamExecutor = buildStreamExecutor();

    /**
     * A relayed stream is an endless response, so whichever thread writes it is occupied
     * until the viewer leaves. That thread must not be an Undertow worker: this server has
     * only {@code ioThreads * 8} of them (32 on the deployment's 4 cores), and holding them
     * open would starve every ordinary API request. Returning a StreamingResponseBody moves
     * the write onto this pool instead - the value is isolation, not thread savings.
     * <p>
     * It must also be built this way rather than as a {@code ThreadPoolTaskExecutor} bean.
     * TaskExecutionApplicationContextInitializer registers a BeanPostProcessor that wraps
     * any {@code ThreadPoolTaskExecutor} or {@code ThreadPoolExecutor} bean in
     * TtlTaskExecutors, whose wrapper implements {@code TaskExecutor} but NOT
     * {@code AsyncTaskExecutor} - so Spring MVC would silently reject it and fall back to
     * an unbounded SimpleAsyncTaskExecutor, spawning a fresh unpooled thread per viewer.
     * Exposing a ConcurrentTaskExecutor keeps the inner pool out of that post-processor's
     * reach; the raw ThreadPoolExecutor below is a constructor argument, never a bean.
     */
    private static AsyncTaskExecutor buildStreamExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,
                MAX_CONCURRENT_STREAMS,
                60L, TimeUnit.SECONDS,
                // Hand off directly: queueing a stream would leave a viewer waiting on a
                // response that never starts. Admission is bounded by a semaphore in the
                // relay service instead, so a rejection here would be a bug, not a limit.
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "edgeai-stream");
                    // Daemon so an in-flight stream cannot hold up JVM shutdown.
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        return new ConcurrentTaskExecutor(pool);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(streamExecutor);
        // An endless response must never be timed out from under the viewer. This is
        // application-wide, but nothing else in this application uses async MVC, and the
        // alternative it replaces is the unbounded default described above.
        configurer.setDefaultTimeout(-1);
    }

    /**
     * Dedicated client for camera requests, with certificate verification disabled.
     * <p>
     * These boxes present self-signed certificates, so there is nothing to verify against.
     * That makes this the most security-sensitive object in the module, and the reason it
     * is a private bean used only by the relay: it must never become the shared client for
     * other outbound calls. The exposure is bounded by the relay only ever connecting to a
     * host taken from stored configuration, never from the request.
     */
    @Bean("edgeAiStreamHttpClient")
    public OkHttpClient edgeAiStreamHttpClient() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // no client certificates are used
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // deliberately unverified - see the class comment
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());

        return new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                .hostnameVerifier((hostname, session) -> true)
                .connectTimeout(5, TimeUnit.SECONDS)
                // No call timeout: a call that is meant to last as long as someone is
                // watching cannot have a deadline. The read timeout still applies, so a
                // camera that stops sending frames is detected rather than hanging forever.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }
}
