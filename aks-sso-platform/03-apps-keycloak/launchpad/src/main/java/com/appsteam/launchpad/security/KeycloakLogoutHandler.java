package com.appsteam.launchpad.security;

import com.appsteam.launchpad.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;


/**
 * RP-initiated logout without OIDC discovery: after the local session is gone, send the browser to Keycloak's
 * end-session endpoint with the ID token hint so the SSO session ends too, then back to the launch page.
 * (Spring's OidcClientInitiatedLogoutSuccessHandler needs the discovered end_session_endpoint, which we avoid
 * because discovery over the self-signed public URL would fail inside the cluster.)
 */
public class KeycloakLogoutHandler extends SimpleUrlLogoutSuccessHandler {

    private final AppProperties props;
    private final ClientRegistrationRepository registrations;

    public KeycloakLogoutHandler(AppProperties props, ClientRegistrationRepository registrations) {
        this.props = props;
        this.registrations = registrations;
        setDefaultTargetUrl("/");
    }

    @Override
    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        String postLogout = props.publicUrl() + "/";
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
            ClientRegistration reg = registrations.findByRegistrationId("keycloak");
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(props.keycloak().endSessionUrl())
                    .queryParam("post_logout_redirect_uri", postLogout)
                    .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue());
            if (reg != null) {
                b.queryParam("client_id", reg.getClientId());
            }
            return b.build().encode().toUriString();
        }
        return postLogout;
    }
}
