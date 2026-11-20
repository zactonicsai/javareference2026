package com.appsteam.launchpad.catalog;

import com.appsteam.launchpad.config.AppProperties;
import com.appsteam.launchpad.security.CurrentUser;
import com.appsteam.launchpad.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view over the configured services and projects, with the access rules in one place:
 * <ul>
 *   <li>a service with no groups is open to everybody;</li>
 *   <li>otherwise the user needs one of the groups — or the admin group;</li>
 *   <li>a project needs its group — or the admin group.</li>
 * </ul>
 */
@Service
public class ServiceCatalog {

    private final AppProperties props;

    public ServiceCatalog(AppProperties props) {
        this.props = props;
    }

    public List<ServiceDefinition> services() {
        return props.allServices();
    }

    public ServiceDefinition service(String id) {
        return findService(id).orElseThrow(() -> new NotFoundException("No service with id '" + id + "'"));
    }

    public Optional<ServiceDefinition> findService(String id) {
        return services().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public List<ProjectDefinition> projects() {
        return props.projects();
    }

    public ProjectDefinition project(String id) {
        return props.projects().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("No project with id '" + id + "'"));
    }

    public boolean canOpen(ServiceDefinition service, CurrentUser user) {
        if (service.isPublic()) {
            return true;
        }
        return user.isAuthenticated() && (user.isAdmin() || service.groups().stream().anyMatch(user::inGroup));
    }

    public boolean canOpen(ProjectDefinition project, CurrentUser user) {
        return user.isAuthenticated() && (user.isAdmin() || user.inGroup(project.group()));
    }

    public List<ProjectDefinition> projectsFor(CurrentUser user) {
        return projects().stream().filter(p -> canOpen(p, user)).toList();
    }

    public String publicUrl() {
        return props.publicUrl();
    }
}
