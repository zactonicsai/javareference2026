package com.appsteam.launchpad.web;

import com.appsteam.launchpad.config.AppProperties;
import com.appsteam.launchpad.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts {@code user}, {@code app} and {@code keycloak} into every template's model (no thymeleaf-extras needed).
 */
@ControllerAdvice
public class UserModelAdvice {

    private final AppProperties props;

    public UserModelAdvice(AppProperties props) {
        this.props = props;
    }

    @ModelAttribute("user")
    public CurrentUser user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return CurrentUser.from(auth, props.adminGroup());
    }

    @ModelAttribute("app")
    public AppProperties app() {
        return props;
    }

    @ModelAttribute("keycloak")
    public AppProperties.Keycloak keycloak() {
        return props.keycloak();
    }
}
