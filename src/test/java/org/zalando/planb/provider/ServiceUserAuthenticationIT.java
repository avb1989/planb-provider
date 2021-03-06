package org.zalando.planb.provider;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.zalando.planb.provider.api.Client;
import org.zalando.planb.provider.api.User;

import java.net.URI;
import java.util.Base64;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.RequestEntity.post;
import static org.springframework.http.RequestEntity.put;
import static org.springframework.http.RequestEntity.get;

@ActiveProfiles("it")
public class ServiceUserAuthenticationIT extends AbstractOauthTest {

    private static final ParameterizedTypeReference<Map<String, Object>> METRICS_TYPE = new ParameterizedTypeReference<Map<String, Object>>() {
    };

    @Value("${local.management.port}")
    private int mgmtPort;

    @Test
    public void testServiceUserFlow() throws Exception {
        final String clientId = "test-service_0815";
        final String clientSecret = "cL!3:Nt";
        final String userPassword = "p455W0rD";
        final String userPassword2 = "fooBar";
        final String username = "test-service";
        final String scope = "uid";
        final String realm = "/services";

        // Create the client
        final Client client = new Client();
        client.setIsConfidential(true);
        client.setSecretHash(hashAndEncodePassword(clientSecret));
        client.setScopes(singletonList(scope));
        getRestTemplate().exchange(put(URI.create(getRawSyncBaseUri()+ "/clients" + realm + "/" + clientId))
                .contentType(APPLICATION_JSON)
                .header(AUTHORIZATION, USER1_ACCESS_TOKEN)
                .header("X-Forwarded-For", "0.0.8.15")
                .body(client), Void.class);

        // Create the user
        final User user = new User();
        user.setPasswordHashes(asList(hashAndEncodePassword(userPassword), hashAndEncodePassword(userPassword2)));
        user.setScopes(singletonMap(scope, "test-service"));
        getRestTemplate().exchange(put(URI.create(getRawSyncBaseUri()+ "/users" + realm + "/" + username))
                .contentType(APPLICATION_JSON)
                .header(AUTHORIZATION, USER1_ACCESS_TOKEN)
                .body(user), Void.class);

        // Get an access token for the newly created user using the first password
        final MultiValueMap<String, Object> requestParameters = new LinkedMultiValueMap<>();
        requestParameters.add("realm", realm);
        requestParameters.add("grant_type", "password");
        requestParameters.add("username", username);
        requestParameters.add("password", userPassword);
        requestParameters.add("scope", scope);

        final ResponseEntity<OIDCCreateTokenResponse> response = getRestTemplate().exchange(
                post(getAccessTokenUri())
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ':' + clientSecret).getBytes(UTF_8)))
                        .body(requestParameters),
                OIDCCreateTokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(OK);
        final OIDCCreateTokenResponse tokenResponse = response.getBody();
        assertThat(tokenResponse.getScope()).isEqualTo("uid");
        assertThat(tokenResponse.getTokenType()).isEqualTo(OAuth2AccessToken.BEARER_TYPE);
        assertThat(tokenResponse.getRealm()).isEqualTo("/services");
        assertThat(tokenResponse.getAccessToken()).isNotEmpty();
        assertThat(tokenResponse.getIdToken()).isNull();
        assertThat(tokenResponse.getAccessToken().split("\\.")).hasSize(3);

        // Get an access token for the newly created user using the second password
        final MultiValueMap<String, Object> requestParameters2 = new LinkedMultiValueMap<>();
        requestParameters2.add("realm", realm);
        requestParameters2.add("grant_type", "password");
        requestParameters2.add("username", username);
        requestParameters2.add("password", userPassword2);
        requestParameters2.add("scope", scope);

        final ResponseEntity<OIDCCreateTokenResponse> response2 = getRestTemplate().exchange(
                post(getAccessTokenUri())
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ':' + clientSecret).getBytes(UTF_8)))
                        .body(requestParameters2),
                OIDCCreateTokenResponse.class);

        assertThat(response2.getStatusCode()).isEqualTo(OK);
        final OIDCCreateTokenResponse tokenResponse2 = response2.getBody();
        assertThat(tokenResponse2.getScope()).isEqualTo("uid");
        assertThat(tokenResponse2.getTokenType()).isEqualTo(OAuth2AccessToken.BEARER_TYPE);
        assertThat(tokenResponse2.getRealm()).isEqualTo("/services");
        assertThat(tokenResponse2.getAccessToken()).isNotEmpty();
        assertThat(tokenResponse2.getIdToken()).isNull();
        assertThat(tokenResponse2.getAccessToken().split("\\.")).hasSize(3);

        // check metrics
        final URI metricsUrl = URI.create("http://localhost:" + mgmtPort + "/metrics");
        final Map<String, Object> metrics = getRestTemplate().exchange(get(metricsUrl).accept(APPLICATION_JSON).build(), METRICS_TYPE).getBody();
        assertThat(metrics).containsKeys("planb.provider.access_token.services.success.count");
    }

    private String hashAndEncodePassword(String clientSecret) {
        return BCrypt.hashpw(clientSecret, BCrypt.gensalt(4));
    }
}
