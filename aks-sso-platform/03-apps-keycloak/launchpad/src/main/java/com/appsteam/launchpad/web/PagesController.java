package com.appsteam.launchpad.web;

import com.appsteam.launchpad.catalog.ProjectDefinition;
import com.appsteam.launchpad.catalog.ServiceCatalog;
import com.appsteam.launchpad.catalog.ServiceDefinition;
import com.appsteam.launchpad.config.AppProperties;
import com.appsteam.launchpad.security.CurrentUser;
import com.appsteam.launchpad.status.ServiceStatus;
import com.appsteam.launchpad.status.StatusChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HTML pages. Access rules that depend on data (which project, which service) live here; the static
 * rules (signed in or not, admin or not) live in {@link com.appsteam.launchpad.config.SecurityConfig}.
 */
@Controller
public class PagesController {

    private static final Logger log = LoggerFactory.getLogger(PagesController.class);

    private final ServiceCatalog catalog;
    private final StatusChecker status;
    private final AppProperties props;

    public PagesController(ServiceCatalog catalog, StatusChecker status, AppProperties props) {
        this.catalog = catalog;
        this.status = status;
        this.props = props;
    }

    /** Public launch page: every tile is listed; locked tiles show which group opens them. */
    @GetMapping({"/", "/index"})
    public String index(Model model, Authentication auth) {
        CurrentUser user = CurrentUser.from(auth, props.adminGroup());
        List<Tile> tiles = catalog.services().stream()
                .map(s -> new Tile(s, s.resolvedUrl(props.publicUrl()), status.statusOf(s.id()), catalog.canOpen(s, user)))
                .toList();
        model.addAttribute("tiles", tiles);
        model.addAttribute("projects", catalog.projects());
        model.addAttribute("myProjects", catalog.projectsFor(user));
        model.addAttribute("downCount", status.downCount());
        model.addAttribute("now", Clock.now());
        return "index";
    }

    /** Any signed-in user. */
    @GetMapping("/restricted")
    public String restricted(Model model, Authentication auth) {
        model.addAttribute("myProjects", catalog.projectsFor(CurrentUser.from(auth, props.adminGroup())));
        return "restricted";
    }

    /** Members of the project's group (or admins). */
    @GetMapping("/projects/{id}")
    public String project(@PathVariable String id, Model model, Authentication auth) {
        CurrentUser user = CurrentUser.from(auth, props.adminGroup());
        ProjectDefinition project = catalog.project(id);
        if (!catalog.canOpen(project, user)) {
            log.info("user {} denied project {} (needs group {})", user.username(), id, project.group());
            throw new ForbiddenException("Project '" + project.name() + "' is restricted to group '" + project.group() + "'",
                    List.of(project.group(), props.adminGroup()));
        }
        List<Tile> tiles = catalog.services().stream()
                .filter(s -> s.groups().contains(project.group()))
                .map(s -> new Tile(s, s.resolvedUrl(props.publicUrl()), status.statusOf(s.id()), true))
                .toList();
        model.addAttribute("project", project);
        model.addAttribute("tiles", tiles);
        return "project";
    }

    /** Admin group only (enforced by SecurityConfig; this just renders). */
    @GetMapping("/admin")
    public String admin(Model model) {
        Map<String, ServiceStatus> byId = new LinkedHashMap<>();
        for (ServiceStatus s : status.all()) {
            byId.put(s.id(), s);
        }
        model.addAttribute("statusById", byId);
        model.addAttribute("downCount", status.downCount());
        model.addAttribute("services", catalog.services());
        model.addAttribute("projects", catalog.projects());
        model.addAttribute("now", Clock.now());
        return "admin";
    }

    /**
     * Link target of every tile: checks access and health first, so users land on a friendly page instead of a
     * browser error when a service is down.
     */
    @GetMapping("/go/{id}")
    public String go(@PathVariable String id, Authentication auth) {
        CurrentUser user = CurrentUser.from(auth, props.adminGroup());
        ServiceDefinition service = catalog.service(id);
        if (!catalog.canOpen(service, user)) {
            throw new ForbiddenException("Service '" + service.name() + "' is restricted", service.groups());
        }
        ServiceStatus current = status.statusOf(id);
        if (current.isDown()) {
            return "redirect:/unavailable/" + id;
        }
        return "redirect:" + service.resolvedUrl(props.publicUrl());
    }

    /** Notification page shown when a service is down (or a user follows a stale link). */
    @GetMapping("/unavailable/{id}")
    public String unavailable(@PathVariable String id,
                              @RequestParam(name = "retry", required = false) String retry,
                              Model model) {
        ServiceDefinition service = catalog.service(id);
        ServiceStatus current = retry != null ? status.refresh(id) : status.statusOf(id);
        if (retry != null && current.isUp()) {
            return "redirect:/go/" + id;
        }
        model.addAttribute("service", service);
        model.addAttribute("serviceUrl", service.resolvedUrl(props.publicUrl()));
        model.addAttribute("status", current);
        model.addAttribute("now", Clock.now());
        return "unavailable";
    }

    /** View model for one tile. */
    public record Tile(ServiceDefinition service, String href, ServiceStatus status, boolean allowed) {
        public String id() {
            return service.id();
        }
    }
}
