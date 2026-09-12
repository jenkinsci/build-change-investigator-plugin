package io.jenkins.plugins.changeinvestigator.notification.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.Permission;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.security.NotificationPermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/** Authenticated Jenkins-only feedback entry point. External notification links remain read-only. */
public final class FeedbackAction implements Action, StaplerProxy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of(
            "caseId",
            "action",
            "actionId",
            "expectedRevision",
            "candidateId",
            "evidenceRevision",
            "recoveryBuild",
            "correctiveAction",
            "validationBasis",
            "fixCommit",
            "note",
            "confirmationId",
            "muteUntil");
    private final Job<?, ?> job;
    private final UUID jobId;

    public FeedbackAction(Job<?, ?> job) {
        this.job = job;
        UUID existing;
        try {
            existing = FeedbackAccess.existingJobId(job).orElse(null);
        } catch (IOException | RuntimeException e) {
            existing = null;
        }
        jobId = existing;
    }

    @Override
    public Object getTarget() {
        job.checkPermission(Item.READ);
        try {
            if (!FeedbackAccess.authoritative(job, jobId)) throw org.kohsuke.stapler.HttpResponses.notFound();
        } catch (IOException e) {
            throw org.kohsuke.stapler.HttpResponses.notFound();
        }
        var request = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (request != null) {
            String path = request.getRestOfPath();
            if (path.isEmpty() || path.equals("/") || path.equals("/index") || path.equals("/index.jelly"))
                requestedCaseId(request);
        }
        return this;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    public FeedbackView getFeedbackView() {
        getTarget();
        var request = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (request == null) throw org.kohsuke.stapler.HttpResponses.notFound();
        try {
            return FeedbackView.forCase(job, requestedCaseId(request));
        } catch (IOException e) {
            return null;
        }
    }

    private static UUID requestedCaseId(StaplerRequest2 request) {
        String[] values = request.getParameterValues("caseId");
        if (values == null || values.length != 1)
            throw org.kohsuke.stapler.HttpResponses.error(400, "Invalid investigation request");
        try {
            return uuid(values[0]);
        } catch (IllegalArgumentException e) {
            throw org.kohsuke.stapler.HttpResponses.error(400, "Invalid investigation request");
        }
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Investigation human review";
    }

    @Override
    public String getUrlName() {
        return "change-investigation-feedback";
    }

    public static Permission permission(FeedbackRequest.Action action) {
        return switch (action) {
            case ACKNOWLEDGE, NOT_RELATED -> NotificationPermissions.TRIAGE;
            case MUTE, UNMUTE -> NotificationPermissions.MUTE;
            default -> NotificationPermissions.CONFIRM;
        };
    }

    private boolean authorized(Authentication actor, FeedbackRequest.Action action) {
        if (ACL.isAnonymous2(actor) || ACL.SYSTEM2.equals(actor) || !actor.isAuthenticated()) return false;
        try (var ignored = ACL.as2(actor)) {
            return FeedbackAccess.authoritative(job, jobId)
                    && job.hasPermission(Item.READ)
                    && job.hasPermission(permission(action));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @RequirePOST
    public void doSubmit(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        Authentication authentication = Jenkins.getAuthentication2();
        if (ACL.isAnonymous2(authentication)
                || ACL.SYSTEM2.equals(authentication)
                || !authentication.isAuthenticated()) {
            answer(response, 403, "AUTHENTICATION_REQUIRED");
            return;
        }
        try {
            job.checkPermission(Item.READ);
            if (!FeedbackAccess.authoritative(job, jobId)) {
                answer(response, 404, "INVESTIGATION_UNAVAILABLE");
                return;
            }
            long length = request.getContentLengthLong();
            if (length < 0 || length > 16384) {
                answer(response, 413, "REQUEST_TOO_LARGE");
                return;
            }
            String contentType = request.getContentType();
            if (contentType == null
                    || !contentType
                            .toLowerCase(java.util.Locale.ROOT)
                            .startsWith("application/x-www-form-urlencoded")) {
                answer(response, 400, "INVALID_REQUEST");
                return;
            }
            Map<String, String[]> values = request.getParameterMap();
            var crumbIssuer = Jenkins.get().getCrumbIssuer();
            String crumb = crumbIssuer == null ? "" : crumbIssuer.getCrumbRequestField();
            if (values.size() > FIELDS.size() + 1) throw new IllegalArgumentException();
            for (var field : values.entrySet()) {
                if ((!FIELDS.contains(field.getKey()) && !field.getKey().equals(crumb)) || field.getValue().length != 1)
                    throw new IllegalArgumentException();
                if (field.getValue()[0].length() > 4096) throw new IllegalArgumentException();
            }
            UUID caseId = uuid(value(values, "caseId"));
            FeedbackRequest.Action action = FeedbackRequest.Action.valueOf(value(values, "action"));
            if (!authorized(authentication, action)) {
                answer(response, 403, "PERMISSION_DENIED");
                return;
            }
            var saved = FeedbackAccess.read(job, caseId);
            if (saved.isEmpty() || !saved.get().jobId().equals(jobId)) {
                answer(response, 404, "INVESTIGATION_UNAVAILABLE");
                return;
            }
            String confirmation = value(values, "confirmationId");
            var command = new FeedbackRequest(
                    action,
                    uuid(value(values, "actionId")),
                    number(values, "expectedRevision", -1),
                    value(values, "candidateId"),
                    number(values, "evidenceRevision", -1),
                    value(values, "recoveryBuild"),
                    value(values, "correctiveAction"),
                    value(values, "validationBasis"),
                    value(values, "fixCommit"),
                    value(values, "note"),
                    confirmation.isBlank() ? null : uuid(confirmation),
                    List.of(),
                    number(values, "muteUntil", 0));
            User user = User.getById(authentication.getName(), false);
            var actor = new FeedbackActor(
                    authentication.getName(), user == null ? authentication.getName() : user.getDisplayName());
            var controllerMarker = Jenkins.get().getRootDir().toPath().resolve("bci-notification-controller-id");
            if (!Files.isRegularFile(controllerMarker, LinkOption.NOFOLLOW_LINKS) || Files.size(controllerMarker) > 40)
                throw new IOException("Existing controller identity required");
            UUID controllerId = uuid(Files.readString(controllerMarker).trim());
            var engine = new NotificationEngine(
                    job.getRootDir().toPath(), jobId, Jenkins.get().getRootDir().toPath(), controllerId);
            var result = engine.feedback(
                    caseId,
                    command,
                    actor,
                    () -> authorized(authentication, action),
                    FeedbackSecrets.forJob(job),
                    System.currentTimeMillis());
            response.setStatus(200);
            response.setContentType("application/json;charset=UTF-8");
            JSON.writeValue(
                    response.getWriter(),
                    Map.of(
                            "revision",
                            result.revision(),
                            "recordId",
                            result.recordId().toString(),
                            "duplicate",
                            result.duplicate(),
                            "code",
                            result.code()));
        } catch (AccessDeniedException e) {
            answer(response, 403, "PERMISSION_DENIED");
        } catch (IllegalArgumentException e) {
            answer(response, 400, "INVALID_REQUEST");
        } catch (IOException | LinkageError e) {
            answer(response, 409, "INVESTIGATION_CHANGED");
        } catch (RuntimeException e) {
            answer(response, 409, "FEEDBACK_UNAVAILABLE");
        }
    }

    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    private static String value(Map<String, String[]> values, String key) {
        return values.containsKey(key) ? values.get(key)[0] : "";
    }

    private static long number(Map<String, String[]> values, String key, long fallback) {
        String text = value(values, key);
        return text.isBlank() ? fallback : Long.parseLong(text);
    }

    private static void answer(StaplerResponse2 response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(response.getWriter(), Map.of("code", code));
    }

    @Extension
    public static final class Factory extends TransientActionFactory<Job> {
        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Collection<? extends Action> createFor(Job target) {
            return List.of(new FeedbackAction(target));
        }
    }
}
