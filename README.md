<p align="center">
  <img src="https://pac4j.github.io/pac4j/img/logo-vertx.png" width="300" />
</p>

The `vertx-pac4j` project is an **easy and powerful security library for Vertx 3** web applications which supports authentication and authorization, but also application logout and advanced features like CSRF protection. It's available under the Apache 2 license and based on the **[pac4j security engine](https://github.com/pac4j/pac4j)**.

It supports most authentication mechanisms, called [**clients**](https://github.com/pac4j/pac4j/wiki/Clients):

- **indirect / stateful clients** are for UI when the user authenticates once at an external provider (like Facebook, a CAS server...) or via a local form (or basic auth popup)  
- **direct / stateless clients** are for web services when credentials (like basic auth, tokens...) are passed for each HTTP request.

See the [authentication flows](https://github.com/pac4j/pac4j/wiki/Authentication-flows).

| The authentication mechanism you want | The `pac4j-*` submodule(s) you must use
|---------------------------------------|----------------------------------------
| OAuth (1.0 & 2.0): Facebook, Twitter, Google, Yahoo, LinkedIn, Github... | `pac4j-oauth`
| CAS (1.0, 2.0, 3.0, SAML, logout, proxy) | `pac4j-cas`
| SAML (2.0) | `pac4j-saml`
| OpenID Connect (1.0) | `pac4j-oidc`
| HTTP (form, basic auth, IP, header, cookie, GET/POST parameter)<br />+<br />JWT<br />or LDAP<br />or Relational DB<br />or MongoDB<br />or Stormpath<br />or CAS REST API| `pac4j-http`<br />+<br />`pac4j-jwt`<br />or `pac4j-ldap`<br />or `pac4j-sql`<br />or `pac4j-mongo`<br />or `pac4j-stormpath`<br />or `pac4j-cas`
| Google App Engine UserService | `pac4j-gae`
| OpenID | `pac4j-openid`

It also supports many authorization checks, called [**authorizers**](https://github.com/pac4j/pac4j/wiki/Authorizers) available in the `pac4j-core` (and `pac4j-http`) submodules: role / permission checks, IP check, profile type verification, HTTP method verification... as well as regular security protections for CSRF, XSS, cache control, Xframe...

For vert.x 2 and previous, use vertx-pac4j 1.1.x - this codebase can be found at [1.1.x](https://github.com/pac4j/vertx-pac4j/tree/vertx-pac4j-1.1.x)


## How to use it?

First, you need to add a dependency on this library as well as on the appropriate `pac4j` submodules. Then, you must define the [**clients**](https://github.com/pac4j/pac4j/wiki/Clients) for authentication and the [**authorizers**](https://github.com/pac4j/pac4j/wiki/Authorizers) to check authorizations.

Define the `CallbackHandler` to finish authentication processes if you use indirect clients (like Facebook).

Use the `RequiresAuthenticationHandler` to secure the urls of your web application (using the `clientName` parameter for authentication and the `authorizerName` parameter for authorizations).

Just follow these easy steps:


### Add the required dependencies (`vertx-pac4j` + `pac4j-*` libraries)

You need to add a dependency on the `vertx-pac4j` library (<em>groupId</em>: **org.pac4j**, *version*: **2.0.1**) as well as on the appropriate `pac4j` submodules (<em>groupId</em>: **org.pac4j**, *version*: **1.8.5**): the `pac4j-oauth` dependency for OAuth support, the `pac4j-cas` dependency for CAS support, the `pac4j-ldap` module for LDAP authentication, ...

All released artifacts are available in the [Maven central repository](http://search.maven.org/#search%7Cga%7C1%7Cpac4j).


### Define the configuration (`Config` + `Clients` + `XXXClient` + `Authorizer`)

Each authentication mechanism (Facebook, Twitter, a CAS server...) is defined by a client (implementing the `org.pac4j.core.client.Client` interface). All clients must be gathered in a `org.pac4j.core.client.Clients` class.

All the `Clients` and the authorizers must be gathered in a `Config` object (which can be itself build in a `org.pac4j.core.config.ConfigFactory`).  
For example:

    final OidcClient oidcClient = new OidcClient();
    oidcClient.setClientID("id");
    oidcClient.setSecret("secret");
    oidcClient.setDiscoveryURI("https://accounts.google.com/.well-known/openid-configuration");
    oidcClient.setUseNonce(true);
    oidcClient.addCustomParam("prompt", "consent");

    final SAML2ClientConfiguration cfg = new SAML2ClientConfiguration("resource:samlKeystore.jks", "pac4j-demo-passwd", "pac4j-demo-passwd", "resource:metadata-okta.xml");
    cfg.setMaximumAuthenticationLifetime(3600);
    cfg.setServiceProviderEntityId("http://localhost:8080/callback?client_name=SAML2Client");
    cfg.setServiceProviderMetadataPath("sp-metadata.xml");
    final SAML2Client saml2Client = new SAML2Client(cfg);

    final FacebookClient facebookClient = new FacebookClient("fbId", "fbSecret");
    final TwitterClient twitterClient = new TwitterClient("twId", "twSecret");
     
    final FormClient formClient = new FormClient("http://localhost:8080/theForm.jsp", new SimpleTestUsernamePasswordAuthenticator());
    final IndirectBasicAuthClient basicAuthClient = new IndirectBasicAuthClient(new SimpleTestUsernamePasswordAuthenticator());
     
    final CasClient casClient = new CasClient("http://mycasserver/login");
     
    final ParameterClient parameterClient = new ParameterClient("token", new JwtAuthenticator("salt"));
     
    final Clients clients = new Clients("http://localhost:8080/callback", oidcClient, saml2Client, facebookClient, twitterClient, formClient, basicAuthClient, casClient, parameterClient);
     
    final Config config = new Config(clients);
    config.addAuthorizer("admin", new RequireAnyRoleAuthorizer("ROLE_ADMIN"));
    config.addAuthorizer("custom", new CustomAuthorizer());

"http://localhost:8080/callback" is the url of the callback endpoint (see below). It may not be defined for REST support / direct clients only.


### Define the callback endpoint (only for stateful / indirect authentication mechanisms)

Indirect clients rely on external identity providers (like Facebook) and thus require to define a callback endpoint in the application where the user will be redirected after login at the identity provider. For REST support / direct clients only, this callback endpoint is not necessary.  
It must be defined for your router (to handle callbacks on the /callback url):

    final CallbackHandler callbackHandler = new CallbackHandler(vertx, config);
    router.get("/callback").handler(callbackHandler);
    router.post("/callback").handler(BodyHandler.create().setMergeFormAttributes(true));
    router.post("/callback").handler(callbackHandler);

The `defaultUrl` parameter can be set for the `CallbackHandler` to define where the user will be redirected after login if no url was originally requested.


### Protect an url (authentication + authorization)

You can protect an url and require the user to be authenticated by a client (and optionally have the appropriate authorizations) by using the `RequiresAuthenticationHandler` and `Pac4jAuthProvider`:

    Pac4jAuthProvider authProvider = new Pac4jAuthProvider();
    Pac4jAuthHandlerOptions options = new Pac4jAuthHandlerOptions().withClientName(clientNames);
    if (authName != null) {
       options = options.withAuthorizerName(authName);
    }
    router.get(url).handler(new RequiresAuthenticationHandler(vertx, config, authProvider, options));

Here are the available parameters of the `Pac4jAuthHandlerOptions`:

- `clientName` (optional): the list of client names (separated by commas) used for authentication. If the user is not authenticated, direct clients are tried successively then if the user is still not authenticated and if the first client is an indirect one, this client is used to start the authentication. Otherwise, a 401 HTTP error is returned. If the *client_name* request parameter is provided, only the matching client is selected
- `authorizerName` (optional): the list of authorizer names (separated by commas) used to check authorizations. If the user is not authorized, a 403 HTTP error is returned. By default (if blank), the user only requires to be authenticated to access the resource
- `matcherName` (optional): the list of matcher names (separated by commas) that the request must satisfy to apply authentication / authorization. By default, all requests are checked.

### Get the user profile

You can test if the user is authenticated using the `VertxProfileManager.isAuthenticated()` method or get the user profile using the `VertxProfileManager.get(true)` method (`false` not to use the session, but only the current HTTP request):

    ProfileManager<CommonProfile> profileManager = new VertxProfileManager<>(new VertxWebContext(rc));
    CommonProfile profile = profileManager.get(true);

The retrieved profile is at least a `CommonProfile`, from which you can retrieve the most common properties that all profiles share. But you can also cast the user profile to the appropriate profile according to the provider used for authentication. For example, after a Facebook authentication:
 
    FacebookProfile facebookProfile = (FacebookProfile) commonProfile;


### Logout

You can log out the current authenticated user using the `ApplicationLogoutHandler`:

    router.get("/logout").handler(new ApplicationLogoutHandler());

To perfom the logout, you must call the /logout url. A blank page is displayed by default unless an *url* request parameter is provided. In that case, the user will be redirected to this specified url (if it matches the logout url pattern defined) or to the default logout url otherwise.

The following parameters can be defined on the `ApplicationLogoutHandler`:

- `defaultUrl` (optional): the default logout url if the provided *url* parameter does not match the `logoutUrlPattern` (by default: /)
- `logoutUrlPattern` (optional): the logout url pattern that the logout url must match (it's a security check, only relative urls are allowed by default).


## Demo

The demo webapp: [vertx-pac4j-demo](https://github.com/pac4j/vertx-pac4j-demo) is available for tests and implement many authentication mechanisms: Facebook, Twitter, form, basic auth, CAS, SAML, OpenID Connect, JWT...


## Release notes

See the [release notes](https://github.com/pac4j/vertx-pac4j/wiki/Release-Notes). Learn more by browsing the [vertx-pac4j Javadoc](http://www.javadoc.io/doc/org.pac4j/vertx-pac4j/2.0.1) and the [pac4j Javadoc](http://www.pac4j.org/apidocs/pac4j/1.8.5/index.html).


## Need help?

If you have any question, please use the following mailing lists:

- [pac4j users](https://groups.google.com/forum/?hl=en#!forum/pac4j-users)
- [pac4j developers](https://groups.google.com/forum/?hl=en#!forum/pac4j-dev)

## Development

The version 2.0.2-SNAPSHOT is under development.

Maven artifacts are built via Travis: [![Build Status](https://travis-ci.org/pac4j/vertx-pac4j.png?branch=master)](https://travis-ci.org/pac4j/vertx-pac4j) and available in the [Sonatype snapshots repository](https://oss.sonatype.org/content/repositories/snapshots/org/pac4j). This repository must be added in the Maven *pom.xml* file for example:

    <repositories>
      <repository>
        <id>sonatype-nexus-snapshots</id>
        <name>Sonatype Nexus Snapshots</name>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        <releases>
          <enabled>false</enabled>
        </releases>
        <snapshots>
          <enabled>true</enabled>
        </snapshots>
      </repository>
    </repositories>
