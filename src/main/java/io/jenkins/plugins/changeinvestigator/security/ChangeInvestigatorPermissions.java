package io.jenkins.plugins.changeinvestigator.security;

import hudson.model.Item;
import hudson.security.Permission;
import hudson.security.PermissionScope;

/**
 * Permissions specific to this plugin. Viewing an investigation is already gated by the
 * standard {@code Item.READ} permission (Jenkins denies access to the whole build subtree,
 * including any attached actions, without it). Triggering a new AI call is a distinct,
 * resource-consuming action, so it is gated by its own permission that composes into the
 * existing job permission matrix.
 */
public final class ChangeInvestigatorPermissions {

    /**
     * Permission required to (re-)run the AI assessment for a build. Implied by {@code Item.BUILD}
     * so anyone already trusted to trigger builds gets it by default; administrators can still
     * grant or revoke it independently in the permission matrix.
     */
    public static final Permission RUN_AI_ANALYSIS = new Permission(
            Item.PERMISSIONS,
            "RunChangeInvestigationAnalysis",
            null,
            Item.BUILD,
            PermissionScope.ITEM);

    private ChangeInvestigatorPermissions() {
    }
}
