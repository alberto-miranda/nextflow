package io.seqera.tower.plugin

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.util.concurrent.UncheckedExecutionException
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dev.failsafe.Failsafe
import dev.failsafe.RetryPolicy
import dev.failsafe.event.EventListener
import dev.failsafe.event.ExecutionAttemptedEvent
import dev.failsafe.function.CheckedSupplier
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.tower.plugin.exception.BadResponseException
import io.seqera.tower.plugin.exception.UnauthorizedException
import io.seqera.tower.plugin.exchange.LicenseTokenRequest
import io.seqera.tower.plugin.exchange.LicenseTokenResponse
import nextflow.Global
import nextflow.Session
import nextflow.SysEnv
import nextflow.exception.AbortOperationException
import nextflow.fusion.FusionConfig
import nextflow.fusion.FusionEnv
import nextflow.platform.PlatformHelper
import nextflow.util.Threads
import org.pf4j.Extension

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.function.Predicate

/**
 * Environment provider for Platform-specific environment variables.
 *
 * @author Alberto Miranda <alberto.miranda@seqera.io>
 */
@Slf4j
@Extension
@CompileStatic
class TowerFusionEnv implements FusionEnv {

    // The path relative to the Platform endpoint where license-scoped JWT tokens are obtained
    private static final String LICENSE_TOKEN_PATH = 'license/token/'

    // Server errors that should trigger a retry
    private static final List<Integer> SERVER_ERRORS = [408, 429, 500, 502, 503, 504]

    // Default connection timeout for HTTP requests
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.of(30, ChronoUnit.SECONDS)

    // Default retry policy settings for HTTP requests: delay, max delay, attempts, and jitter
    private static final Duration DEFAULT_RETRY_POLICY_DELAY = Duration.of(450, ChronoUnit.MILLIS)
    private static final Duration DEFAULT_RETRY_POLICY_MAX_DELAY = Duration.of(90, ChronoUnit.SECONDS)
    private static final int DEFAULT_RETRY_POLICY_MAX_ATTEMPTS = 10
    private static final double DEFAULT_RETRY_POLICY_JITTER = 0.5

    // The HttpClient instance used to send requests
    private final HttpClient httpClient = newDefaultHttpClient()

    // The RetryPolicy instance used to retry requests
    private final RetryPolicy retryPolicy = newDefaultRetryPolicy(SERVER_ERRORS)

    // Time-to-live for cached tokens
    private Duration tokenTTL = Duration.of(1, ChronoUnit.HOURS)

    // Cache used for storing license tokens
    private Cache<String, LicenseTokenResponse> tokenCache = CacheBuilder.newBuilder()
        .expireAfterWrite(tokenTTL)
        .build()

    // Nextflow session
    private final Session session

    // Platform endpoint to use for requests
    private final String endpoint

    // Platform access token to use for requests
    private final String accessToken

    /**
     * Constructor for the class. It initializes the session, endpoint, and access token.
     */
    TowerFusionEnv() {
        this.session = Global.session as Session
        final towerConfig = session.config.navigate('tower') as Map ?: [:]
        final env = SysEnv.get()
        this.endpoint = PlatformHelper.getEndpoint(towerConfig, env)
        this.accessToken = PlatformHelper.getAccessToken(towerConfig, env)
    }

    /**
     * Return any environment variables relevant to Fusion execution. This method is called
     * by {@link nextflow.fusion.FusionEnvProvider#getEnvironment} to determine which
     * environment variables are needed for the current run.
     *
     * @param scheme The scheme for which the environment variables are needed (currently unused)
     * @param config The Fusion configuration object
     * @return A map of environment variables
     */
    @Override
    Map<String, String> getEnvironment(String scheme, FusionConfig config) {
        final product = config.sku()
        final version = config.version()

        try {
            final token = getLicenseToken(product, version)
            return Map.of('FUSION_LICENSE_TOKEN', token)
        }
        catch (Exception e) {
            log.warn1("Error retrieving Fusion license information: ${e.message}", causedBy:e, cacheKey:'getLicenseTokenException')
            return Map.of()
        }
    }

    /**
     * Send a request to Platform to obtain a license-scoped JWT for Fusion. The request is authenticated using the
     * Platform access token provided in the configuration of the current session.
     *
     * @throws AbortOperationException if a Platform access token cannot be found
     *
     * @return The signed JWT token
     */
    protected String getLicenseToken(String product, String version) throws AbortOperationException {
        if (accessToken == null) {
            throw new AbortOperationException("Missing Platform access token -- Make sure there's a variable TOWER_ACCESS_TOKEN in your environment")
        }

        final req = new LicenseTokenRequest(product: product, version: version)

        try {
            final key = '${product}-${version}'
            int i=0
            while( i++<2 ) {
                final resp = tokenCache.get(key, () -> sendRequest(req))
                // Check if the cached response has expired
                // It's needed because the JWT token TTL in the cache (1 hour) and its expiration date (e.g. 1 day?) are not sync'ed,
                // so it could happen that we get a token from the cache which was valid at the time of insertion but is now expired.
                if( resp.expirationDate.before(new Date()) ) {
                    log.debug "Cached token already expired; refreshing"
                    tokenCache.invalidate(key)
                }
                else
                    return resp.signedToken
            }
        } catch (UncheckedExecutionException e) {
            throw e.getCause()
        }
    }

    /**************************************************************************
     * Helper methods
     *************************************************************************/

    /**
     * Create a new HttpClient instance with default settings
     * @return The new HttpClient instance
     */
    private static HttpClient newDefaultHttpClient() {
        final builder = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager())
            .connectTimeout(DEFAULT_CONNECTION_TIMEOUT)
        // use virtual threads executor if enabled
        if ( Threads.useVirtual() ) {
            builder.executor(Executors.newVirtualThreadPerTaskExecutor())
        }
        // build and return the new client
        return builder.build()
    }

    /**
     * Create a new RetryPolicy instance with default settings and the given list of retryable errors. With this policy,
     * a request is retried on IOExceptions and any server errors defined in errorsToRetry. The number of retries, delay,
     * max delay, and jitter are controlled by the corresponding values defined at class level.
     *
     * @return The new RetryPolicy instance
     */
    private static <T> RetryPolicy<HttpResponse<T>> newDefaultRetryPolicy(List<Integer> errorsToRetry) {

        final retryOnException = (e -> e instanceof IOException) as Predicate<? extends Throwable>
        final retryOnStatusCode = ((HttpResponse<T> resp) -> resp.statusCode() in errorsToRetry) as Predicate<HttpResponse<T>>

        final listener = new EventListener<ExecutionAttemptedEvent<HttpResponse<T>>>() {
            @Override
            void accept(ExecutionAttemptedEvent event) throws Throwable {
                def msg = "connection failure - attempt: ${event.attemptCount}"
                if (event.lastResult != null)
                    msg += "; response: ${event.lastResult}"
                if (event.lastFailure != null)
                    msg += "; exception: [${event.lastFailure.class.name}] ${event.lastFailure.message}"
                log.debug(msg)
            }
        }
        return RetryPolicy.<HttpResponse<T>> builder()
            .handleIf(retryOnException)
            .handleResultIf(retryOnStatusCode)
            .withBackoff(DEFAULT_RETRY_POLICY_DELAY.toMillis(), DEFAULT_RETRY_POLICY_MAX_DELAY.toMillis(), ChronoUnit.MILLIS)
            .withMaxAttempts(DEFAULT_RETRY_POLICY_MAX_ATTEMPTS)
            .withJitter(DEFAULT_RETRY_POLICY_JITTER)
            .onRetry(listener)
            .build()
    }

    /**
     * Send an HTTP request and return the response. This method automatically retries the request according to the
     * given RetryPolicy.
     *
     * @param req The HttpRequest to send
     * @return The HttpResponse received
     */
    private <T> HttpResponse<String> safeHttpSend(HttpRequest req, RetryPolicy<T> policy) {
        return Failsafe.with(policy).get(
            () -> {
                log.debug "Http request: method=${req.method()}; uri=${req.uri()}; request=${req}"
                final resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                log.debug "Http response: statusCode=${resp.statusCode()}; body=${resp.body()}"
                return resp
            } as CheckedSupplier
        ) as HttpResponse<String>
    }

    /**
     * Create a {@link HttpRequest} representing a {@link LicenseTokenRequest} object
     *
     * @param req The LicenseTokenRequest object
     * @return The resulting HttpRequest object
     */
    private HttpRequest makeHttpRequest(LicenseTokenRequest req) {
        return HttpRequest.newBuilder()
            .uri(URI.create("${endpoint}/${LICENSE_TOKEN_PATH}").normalize())
            .header('Content-Type', 'application/json')
            .header('Authorization', "Bearer ${accessToken}")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    serializeToJson(req)
                )
            )
            .build()
    }

    /**
     * Serialize a {@link LicenseTokenRequest} object into a JSON string
     *
     * @param req The LicenseTokenRequest object
     * @return The resulting JSON string
     */
    private static String serializeToJson(LicenseTokenRequest req) {
        return new Gson().toJson(req)
    }

    /**
     * Parse a JSON string into a {@link LicenseTokenResponse} object
     *
     * @param resp The String containing the JSON representation of the LicenseTokenResponse object
     * @return The resulting LicenseTokenResponse object
     *
     * @throws JsonSyntaxException if the JSON string is not well-formed
     */
    private static LicenseTokenResponse parseLicenseTokenResponse(String resp) throws JsonSyntaxException {
        return new Gson().fromJson(resp, LicenseTokenResponse.class)
    }

    /**
     * Request a license token from Platform.
     *
     * @param req The LicenseTokenRequest object
     * @return The LicenseTokenResponse object
     *
     * @throws AbortOperationException if a Platform access token cannot be found
     * @throws UnauthorizedException if the access token is invalid
     * @throws BadResponseException if the response is not as expected
     * @throws IllegalStateException if the request cannot be sent
     */
    private LicenseTokenResponse sendRequest(LicenseTokenRequest req) throws AbortOperationException, UnauthorizedException, BadResponseException, IllegalStateException {

        final httpReq = makeHttpRequest(req)

        try {
            final resp = safeHttpSend(httpReq, retryPolicy)

            if( resp.statusCode() == 200 ) {
                final ret = parseLicenseTokenResponse(resp.body())
                return ret
            }

            if( resp.statusCode() == 401 ) {
                throw new UnauthorizedException("Unauthorized [401] - Verify you have provided a valid access token")
            }

            throw new BadResponseException("Invalid response: ${httpReq.method()} ${httpReq.uri()} [${resp.statusCode()}] ${resp.body()}")
        } catch (IOException e) {
            throw new IllegalStateException("Unable to send request to '${httpReq.uri()}' : ${e.message}")
        }
    }
}
