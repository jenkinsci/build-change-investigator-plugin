package io.jenkins.plugins.changeinvestigator.slack.config;

import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Native administrator management of explicit SCM identity mappings. */
@Extension
public final class SlackResponderManagement extends ManagementLink implements StaplerProxy {
    private static final int PAGE_SIZE = 25;

    @Override
    public String getIconFileName() {
        return "symbol-user";
    }

    @Override
    public String getDisplayName() {
        return "BCI Slack responder mappings";
    }

    @Override
    public String getDescription() {
        return "Manage explicit SCM identity to Slack Member ID mappings.";
    }

    @Override
    public String getUrlName() {
        return "bci-slack-responders";
    }

    @Override
    public Permission getRequiredPermission() {
        return Jenkins.ADMINISTER;
    }

    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    public View getView() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        StaplerRequest2 request = Stapler.getCurrentRequest2();
        String query = ResponderMapping.bounded(request.getParameter("q"), 256);
        List<ResponderMapping> all = SlackConfiguration.get().getResponderMappings();
        String normalized = query.toLowerCase(Locale.ROOT);
        List<ResponderMapping> matching = all.stream()
                .filter(mapping -> mapping.getIdentity()
                                .toLowerCase(Locale.ROOT)
                                .contains(normalized)
                        || mapping.getSlackUser().toLowerCase(Locale.ROOT).contains(normalized)
                        || mapping.getDisplayName().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
        int pages = Math.max(1, (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = 1;
        String requested = request.getParameter("page");
        if (requested != null && requested.matches("[0-9]{1,6}"))
            page = Math.max(1, Math.min(pages, Integer.parseInt(requested)));
        int start = (page - 1) * PAGE_SIZE;
        String id = ResponderMapping.bounded(request.getParameter("id"), 64);
        ResponderMapping selected = all.stream()
                .filter(mapping -> mapping.getId().equals(id))
                .findFirst()
                .orElse(null);
        String identity = selected == null ? "" : selected.getIdentity();
        String member = selected == null ? "" : selected.getSlackUser();
        String error = (String) request.getAttribute("mappingError");
        if (error != null) {
            identity = ResponderMapping.bounded(request.getParameter("identity"), 256);
            member = ResponderMapping.bounded(request.getParameter("slackUser"), 64);
        }
        return new View(
                query,
                matching.subList(start, Math.min(matching.size(), start + PAGE_SIZE)),
                matching.size(),
                all.size(),
                page,
                pages,
                selected == null ? "" : selected.getId(),
                identity,
                member,
                error);
    }

    public boolean isDifferentWorkspace(ResponderMapping mapping) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String current = SlackConfiguration.get().getVerifiedWorkspaceId();
        return !current.isBlank()
                && !mapping.getWorkspaceId().isBlank()
                && !SlackConfiguration.get().isMappingWorkspaceCurrent(mapping);
    }

    public boolean hasDifferentWorkspaceMappings() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return SlackConfiguration.get().getResponderMappings().stream().anyMatch(this::isDifferentWorkspace);
    }

    public String getWorkspace() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        SlackConfiguration config = SlackConfiguration.get();
        return config.isWorkspaceVerified() ? config.getVerifiedWorkspaceName() : "Not verified yet";
    }

    public String getMappingStatus(ResponderMapping mapping) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return SlackConfiguration.get().getMappingStatus(mapping);
    }

    public String getValidationNotice() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return (String) Stapler.getCurrentRequest2().getAttribute("mappingNotice");
    }

    @RequirePOST
    public void doValidate(StaplerRequest2 request, StaplerResponse2 response, @QueryParameter String id)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            SlackConfiguration config = SlackConfiguration.get();
            boolean workspaceVerified = config.isWorkspaceVerified();
            boolean validated = workspaceVerified && config.validateMapping(id);
            request.setAttribute(
                    "mappingNotice",
                    !workspaceVerified
                            ? "Verify the Slack connection in System configuration before validating mappings."
                            : validated
                                    ? "Mapping validated for the current workspace."
                                    : "Mapping could not be validated. Check the configured Slack credential and Member ID.");
        } catch (IllegalArgumentException failure) {
            request.setAttribute("mappingError", failure.getMessage());
            response.setStatus(400);
        }
        request.getView(this, "index.jelly").forward(request, response);
    }

    @RequirePOST
    public void doSave(
            StaplerRequest2 request,
            StaplerResponse2 response,
            @QueryParameter String id,
            @QueryParameter String identity,
            @QueryParameter String slackUser)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            if (id == null || id.isBlank()) SlackConfiguration.get().addMapping(identity, slackUser);
            else SlackConfiguration.get().updateMapping(id, identity, slackUser);
            response.sendRedirect2(".");
        } catch (IllegalArgumentException failure) {
            request.setAttribute("mappingError", failure.getMessage());
            String message = failure.getMessage();
            if ("Enter a Git or Jenkins identity of at most 256 characters.".equals(message)
                    || "A responder mapping already exists for this identity in this workspace.".equals(message)) {
                request.setAttribute("mappingErrorField", "identity");
            } else if ("Enter a valid Slack user ID.".equals(message)) {
                request.setAttribute("mappingErrorField", "slackUser");
            }
            response.setStatus(400);
            request.getView(this, "index.jelly").forward(request, response);
        }
    }

    public String getMappingErrorField() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return (String) Stapler.getCurrentRequest2().getAttribute("mappingErrorField");
    }

    @RequirePOST
    public void doRemove(StaplerRequest2 request, StaplerResponse2 response, @QueryParameter String id)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            SlackConfiguration.get().removeMapping(id);
            response.sendRedirect2(".");
        } catch (IllegalArgumentException failure) {
            request.setAttribute("mappingError", failure.getMessage());
            response.setStatus(400);
            request.getView(this, "index.jelly").forward(request, response);
        }
    }

    public record View(
            String query,
            List<ResponderMapping> rows,
            int matching,
            int total,
            int page,
            int pages,
            String id,
            String identity,
            String slackUser,
            String error) {
        public String getQuery() {
            return query;
        }

        public List<ResponderMapping> getRows() {
            return rows;
        }

        public int getMatching() {
            return matching;
        }

        public int getTotal() {
            return total;
        }

        public int getPage() {
            return page;
        }

        public int getPages() {
            return pages;
        }

        public String getId() {
            return id;
        }

        public String getIdentity() {
            return identity;
        }

        public String getSlackUser() {
            return slackUser;
        }

        public String getError() {
            return error;
        }

        public String getPreviousUrl() {
            return "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&page=" + (page - 1);
        }

        public String getNextUrl() {
            return "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&page=" + (page + 1);
        }
    }
}
