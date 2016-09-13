package org.pac4j.vertx;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.AuthHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.UserSessionHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;
import org.junit.Test;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.vertx.auth.Pac4jAuthProvider;
import org.pac4j.vertx.client.TestOAuth1Client;
import org.pac4j.vertx.client.TestOAuth2AuthorizationGenerator;
import org.pac4j.vertx.client.TestOAuth2Client;
import org.pac4j.vertx.handler.impl.ApplicationLogoutHandler;
import org.pac4j.vertx.handler.impl.CallbackDeployingPac4jAuthHandler;
import org.pac4j.vertx.handler.impl.CallbackHandlerOptions;
import org.pac4j.vertx.handler.impl.SecurityHandlerOptions;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * @author Jeremy Prime
 * @since 2.0.0
 */
public class StatefulPac4jAuthHandlerIntegrationTest extends Pac4jAuthHandlerIntegrationTestBase {

    private static final String TEST_CLIENT_ID = "testClient";
    private static final String TEST_CLIENT_SECRET = "testClientSecret";
    private static final String TEST_OAUTH2_SUCCESS_URL = "http://localhost:9292/authSuccess";
    private static final String LOGOUT_URL_FOR_CLIENT = "/logout?url=/";

    private static final Logger LOG = LoggerFactory.getLogger(StatefulPac4jAuthHandlerIntegrationTest.class);

    // This will be our session cookie header for use by requests
    protected AtomicReference<String> sessionCookie = new AtomicReference<>();

    public void startOAuth2ProviderMimic(final String userIdToReturn) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final OAuth2ProviderMimic mimic = new OAuth2ProviderMimic(userIdToReturn);

        vertx.deployVerticle(mimic, result -> latch.countDown());
        latch.await(2, TimeUnit.SECONDS);
    }

    @Test
    public void testSuccessfulOAuth2LoginWithoutAuthorities() throws Exception {

        LOG.info("testSuccessfulOAuth2LoginWithoutAuthorities");
        LOG.debug("Starting auth provider mimic");
        startOAuth2ProviderMimic("testUser1");
        // Start a web server with no required authorities (i.e. only authentication required) for the secured resource
        startWebServer(TEST_OAUTH2_SUCCESS_URL, optionsWithBothNamesProvided(), callbackHandlerOptions(), null);
        loginSuccessfullyExpectingAuthorizedUser(Void -> testComplete());
        await(2, TimeUnit.SECONDS);
    }

    @Test
    public void testSuccessfulOAuth2LoginWithoutAuthorizerName() throws Exception {
        // This should let any user access a protected resource - we'll set up with a user who doesn't have
        // adequate permissions if the authorizer is used
        startOAuth2ProviderMimic("testUser2");
        final String[] permissions = {
                "permission1", "permission2"
        };
        startWebServer(TEST_OAUTH2_SUCCESS_URL, new SecurityHandlerOptions().withClients(TEST_CLIENT_NAME), callbackHandlerOptions(), Arrays.asList(permissions));
        loginSuccessfullyExpectingAuthorizedUser(Void -> testComplete());
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testSuccessfulOAuth2LoginWithInsufficientAuthorities() throws Exception {
        startOAuth2ProviderMimic("testUser2");
        final String[] permissions = {
                "permission1", "permission2"
        };
        startWebServer(TEST_OAUTH2_SUCCESS_URL, optionsWithBothNamesProvided(), callbackHandlerOptions(), Arrays.asList(permissions));
        loginSuccessfullyExpectingUnauthorizedUser(Void -> testComplete());
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testSuccessfulOAuth2LoginWithSufficientAuthorities() throws Exception {
        startOAuth2ProviderMimic("testUser2");
        final String[] permissions = {"permission1"};
        startWebServer(TEST_OAUTH2_SUCCESS_URL, optionsWithBothNamesProvided(), callbackHandlerOptions(),  Arrays.asList(permissions));
        loginSuccessfullyExpectingAuthorizedUser(Void -> testComplete());
        await(1, TimeUnit.SECONDS);
    }

    // Test that subsequent access following successful login doesn't require another set of redirects, assuming session
    // is maintained
    @Test
    public void testSubsequentAccessFollowingSuccessfulLogin() throws Exception {
        startOAuth2ProviderMimic("testUser1");
        // Start a web server with no required authorities (i.e. only authentication required) for the secured resource
        startWebServer(TEST_OAUTH2_SUCCESS_URL, optionsWithBothNamesProvided(), callbackHandlerOptions(), null);
        HttpClient client = vertx.createHttpClient();
        loginSuccessfullyExpectingAuthorizedUser(client, Void -> {

            final HttpClientRequest successfulRequest = client.get(8080, "localhost", "/private/success.html");
            getSessionCookie().ifPresent(cookie -> successfulRequest.putHeader("cookie", cookie));
            successfulRequest.handler(resp -> {

                assertEquals(200, resp.statusCode());
                validateLoginSuccessResponse(resp, v -> testComplete());
            }).end();
        });
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testLogoutRequiresSubsequentReauthentication() throws Exception {
        startOAuth2ProviderMimic("testUser1");
        // Start a web server with no required authorities (i.e. only authentication required) for the secured resource
        startWebServer(TEST_OAUTH2_SUCCESS_URL, optionsWithBothNamesProvided(), callbackHandlerOptions(), null);
        HttpClient client = vertx.createHttpClient();
        loginSuccessfullyExpectingAuthorizedUser(client, Void -> {
            LOG.info("Successfully logged in, now about to logout");
            logout(client, response -> {
                // We don't need to actually bother to redirect to the url, we've validated that we're directed to following
                // logout, what we do need to do, is with our session established, try and connect to a protected url again
                // and ensure we get a redirect to the auth provider as expected
                final HttpClientRequest successfulRequest = client.get(8080, "localhost", "/private/success.html");
                getSessionCookie().ifPresent(cookie -> successfulRequest.putHeader("cookie", cookie));
                successfulRequest.handler(resp -> {
                    assertEquals(302, resp.statusCode());
                    final String redirectToUrl = resp.getHeader("location");
                    LOG.info("RedirectTo: " + redirectToUrl);
                    // Check we're redirecting to a url derived from the auth provider url we passed to the web server
                    assertTrue(redirectToUrl.startsWith(TEST_OAUTH2_SUCCESS_URL));
                    testComplete();
                })
                .end();


            });
        });
        await(1, TimeUnit.SECONDS);
    }

    private CallbackHandlerOptions callbackHandlerOptions() {
        return new CallbackHandlerOptions().setDefaultUrl(Pac4jConstants.DEFAULT_URL).setMultiProfile(false);
    }

    @Override
    protected void validateProtectedResourceContent(JsonObject jsonObject) {
        assertThat(jsonObject.getString("access_token"), is(notNullValue()));
    }

    private void loginSuccessfullyExpectingAuthorizedUser(final Consumer<Void> subsequentActions) throws Exception {
        loginSuccessfullyExpectingAuthorizedUser(vertx.createHttpClient(), subsequentActions);
    }

    private void loginSuccessfullyExpectingAuthorizedUser(final HttpClient client, final Consumer<Void> subsequentActions) throws Exception {
        loginSuccessfully(client, finalRedirectResponse -> {
            assertEquals(200, finalRedirectResponse.statusCode());
            validateLoginSuccessResponse(finalRedirectResponse, subsequentActions);
        });
    }

    private void loginSuccessfullyExpectingUnauthorizedUser(final Consumer<Void> subsequentActions) throws Exception {
        loginSuccessfully(finalRedirectResponse -> {
            assertEquals(403, finalRedirectResponse.statusCode());
            finalRedirectResponse.bodyHandler(body -> {
                assertEquals(FORBIDDEN_BODY, body.toString());
                subsequentActions.accept(null);
            });
        });
    }

    private void loginSuccessfully(final Handler<HttpClientResponse> finalResponseHandler) throws Exception {
        HttpClient client = vertx.createHttpClient();
        loginSuccessfully(client, finalResponseHandler);
    }

    private void logout(final HttpClient client, final Handler<HttpClientResponse> postLogoutActions) {
        final HttpClientRequest logoutRequest = client.get(8080, "localhost", LOGOUT_URL_FOR_CLIENT);
        getSessionCookie().ifPresent(cookie -> logoutRequest.putHeader("cookie", cookie));
        logoutRequest.handler(response -> {
            assertEquals(302, response.statusCode());
            final String redirectToUrl = response.getHeader("location");
            assertEquals(redirectToUrl, "/");
            postLogoutActions.handle(response);
        }).end();
    }

    private void loginSuccessfully(final HttpClient client, final Handler<HttpClientResponse> finalResponseHandler) throws Exception {
        // Attempt to get a private url
        final HttpClientRequest successfulRequest = client.get(8080, "localhost", "/private/success.html");
        successfulRequest.handler(
                // redirect to auth handler
                expectAndHandleRedirect(client,
                        extractCookie(),
                        // redirect to auth response handler
                        expectAndHandleRedirect(client, clientResponse -> {},
                                // redirect to original url if authorized
                                expectAndHandleRedirect(client, httpClientResponse -> {},
                                        finalResponseHandler::handle)))
        )
                .end();

    }

    private Consumer<HttpClientResponse> extractCookie() {
        return clientResponse -> {
            final String setCookie = clientResponse.headers().get("set-cookie");
            assertNotNull(setCookie);
            sessionCookie.set(setCookie); // We're going to want to use this subsequently
        };
    }

    private Handler<HttpClientResponse> expectAndHandleRedirect(final HttpClient client,
                                                                final Consumer<HttpClientResponse> responseConsumer,
                                                                final Handler<HttpClientResponse> redirectResultHandler) {
        return response -> {
            assertEquals(302, response.statusCode());
            responseConsumer.accept(response);
            final String redirectToUrl = response.getHeader("location");
            LOG.info("Redirecting to " + redirectToUrl);
            redirectToUrl(redirectToUrl, client, redirectResultHandler);
        };
    }

    private SecurityHandlerOptions optionsWithBothNamesProvided() {
        return new SecurityHandlerOptions().withAuthorizers(REQUIRE_ALL_AUTHORIZER)
                .withClients(TEST_CLIENT_NAME);
    }

    private void startWebServer(final String baseAuthUrl,
                                final SecurityHandlerOptions options,
                                final CallbackHandlerOptions callbackHandlerOptions,
                                final List<String> requiredPermissions) throws Exception {
        startWebServer(baseAuthUrl, options, callbackHandlerOptions, requiredPermissions, handler -> {
        });
    }

    private void startWebServer(final String baseAuthUrl,
                                final SecurityHandlerOptions options,
                                final CallbackHandlerOptions callbackHandlerOptions,
                                final List<String> requiredPermissions,
                                final Consumer<AuthHandler> handlerDecorator) throws Exception {
        Router router = Router.router(vertx);
        Pac4jAuthProvider authProvider = new Pac4jAuthProvider();

        SessionStore sessionStore = sessionStore();

        router.route().handler(CookieHandler.create());
        router.route().handler(sessionHandler(sessionStore));
        router.route().handler(UserSessionHandler.create(authProvider));

        CallbackDeployingPac4jAuthHandler pac4jAuthHandler = authHandler(router,
                authProvider,
                baseAuthUrl,
                options,
                callbackHandlerOptions,
                requiredPermissions);
        handlerDecorator.accept(pac4jAuthHandler);

        router.route(HttpMethod.GET, "/logout").handler(new ApplicationLogoutHandler());

        startWebServer(router, pac4jAuthHandler);
    }

    private CallbackDeployingPac4jAuthHandler authHandler(final Router router,
                                                          final Pac4jAuthProvider authProvider,
                                                          final String baseAuthUrl,
                                                          final SecurityHandlerOptions options,
                                                          final CallbackHandlerOptions callbackHandlerOptions,
                                                          final List<String> requiredPermissions) {
        return new CallbackDeployingPac4jAuthHandler(vertx, config(new Clients(oAuth2Client(baseAuthUrl), testOAuth1Client()), requiredPermissions), router, authProvider, options, callbackHandlerOptions);
    }

    private void redirectToUrl(final String redirectUrl, final HttpClient client, final Handler<HttpClientResponse> resultHandler) {
        final HttpClientRequest request = client.getAbs(redirectUrl);
        getSessionCookie().ifPresent(cookie -> request.putHeader("cookie", cookie));
        request.handler(resultHandler);
        request.end();
    }

    private Optional<String> getSessionCookie() {
        return Optional.ofNullable(sessionCookie.get());
    }

    private Config config(final Clients clients, List<String> requiredPermissions) {
        clients.setCallbackUrl("http://localhost:8080/authResult");
        return new  Config(clients, authorizers(requiredPermissions));
    }

    private TestOAuth2Client oAuth2Client(final String baseAuthUrl) {
        TestOAuth2Client client = new TestOAuth2Client();
        client.setCallbackUrl("http://localhost:8080/authResult");
        client.setKey(TEST_CLIENT_ID);
        client.setSecret(TEST_CLIENT_SECRET);
        client.setName("TestOAuth2Client");
        client.setIncludeClientNameInCallbackUrl(true);
        client.setAuthorizationUrlTemplate(baseAuthUrl + "?client_id=%s&redirect_uri=%s&state=%s");
        client.addAuthorizationGenerator(new TestOAuth2AuthorizationGenerator());
        return client;
    }

    private TestOAuth1Client testOAuth1Client() {
        TestOAuth1Client client =  new TestOAuth1Client();
        client.setKey(TEST_CLIENT_ID);
        client.setSecret(TEST_CLIENT_SECRET);
        client.setName("TestOAuth1Client");
        return client;
    }

    private SessionHandler sessionHandler(SessionStore sessionStore) {
        return SessionHandler.create(sessionStore).setSessionCookieName("oAuth2Consumer.session");
    }

    private LocalSessionStore sessionStore() {
        return LocalSessionStore.create(vertx);
    }

}
