package io.jenkins.plugins.changeinvestigator.security;

import hudson.model.Item;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;

/**
 * Permissions specific to this plugin. Viewing an investigation is already gated by the
 * standard {@code Item.READ} permission (Jenkins denies access to the whole build subtree,
 * including any attached actions, without it). Triggering a new AI call is a distinct,
 * resource-consuming action, so it is gated by its own permission that composes into the
 * existing job permission matrix.
 */
public final class ChangeInvestigatorPermissions {

    /**
     * Permission required to (re-)run the AI assessment for a build. This is deliberately
     * <b>not</b> implied by {@code Item.BUILD} (or any other job-level permission) - being
     * trusted to trigger builds does not, by itself, authorize spending AI provider budget.
     * It must be granted explicitly by an administrator in the permission matrix.
     *
     * <p>It is implied by {@code Jenkins.ADMINISTER}, matching normal Jenkins semantics: an
     * instance administrator already has effective access to everything and does not need a
     * separate, redundant grant for every plugin-specific permission.
     */
    public static final Permission RUN_AI_ANALYSIS = new Permission(
            Item.PERMISSIONS, "RunChangeInvestigationAnalysis", null, Jenkins.ADMINISTER, PermissionScope.ITEM);

    private ChangeInvestigatorPermissions() {}
}
