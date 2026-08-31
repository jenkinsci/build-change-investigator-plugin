package io.jenkins.plugins.changeinvestigator.security;

import hudson.model.Run;
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
     *
     * <p>Scoped to {@link PermissionScope#RUN} (with group {@link Run#PERMISSIONS}), matching
     * how Jenkins core itself scopes per-build permissions like {@link Run#DELETE} and
     * {@link Run#UPDATE}: this permission only ever governs an action taken against a specific
     * build, never the job/item as a whole. {@code RUN} is contained by {@code ITEM} (see
     * {@link PermissionScope}), so it remains configurable anywhere an item-scoped permission
     * would be - per-project and per-folder Matrix Authorization Strategy tables, as well as
     * the global matrix - without changing where an administrator can grant it.
     */
    public static final Permission RUN_AI_ANALYSIS = new Permission(
            Run.PERMISSIONS,
            "RunChangeInvestigationAnalysis",
            Messages._ChangeInvestigatorPermissions_RunChangeInvestigationAnalysisPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.RUN);

    private ChangeInvestigatorPermissions() {}
}
