package io.jenkins.plugins.changeinvestigator.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Confirms the plugin's custom permission composes correctly into Jenkins' job permission
 * matrix: a user with only read/build access does not get it; a user explicitly granted it
 * does; a Jenkins administrator (holder of {@code Jenkins.ADMINISTER}) does, via normal
 * Jenkins permission semantics, without needing a separate explicit grant.
 */
@WithJenkins
class ChangeInvestigatorPermissionsTest {

    @Test
    void onlyUsersGrantedThePermissionCanRunAnalysis(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("readOnlyUser")
                .grant(Jenkins.READ, Item.READ, ChangeInvestigatorPermissions.RUN_AI_ANALYSIS)
                .everywhere()
                .to("investigatorUser"));

        FreeStyleProject project = jenkins.createFreeStyleProject("permission-test");

        try (ACLContext ctx = ACL.as2(User.getById("readOnlyUser", true).impersonate2())) {
            assertFalse(project.hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS));
        }

        try (ACLContext ctx = ACL.as2(User.getById("investigatorUser", true).impersonate2())) {
            assertTrue(project.hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS));
        }
    }

    @Test
    void buildPermissionAloneDoesNotImplyAiAnalysisPermission(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.BUILD)
                .everywhere()
                .to("builderUser"));

        FreeStyleProject project = jenkins.createFreeStyleProject("build-only-test");

        try (ACLContext ctx = ACL.as2(User.getById("builderUser", true).impersonate2())) {
            assertFalse(
                    project.hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS),
                    "Being trusted to trigger builds must not, by itself, authorize AI analysis");
        }
    }

    @Test
    void jenkinsAdministratorHasAiAnalysisPermissionWithoutExplicitGrant(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("adminUser"));

        FreeStyleProject project = jenkins.createFreeStyleProject("admin-test");

        try (ACLContext ctx = ACL.as2(User.getById("adminUser", true).impersonate2())) {
            assertTrue(
                    project.hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS),
                    "Jenkins.ADMINISTER should imply the plugin's RUN_AI_ANALYSIS permission");
        }
    }

    @Test
    void permissionIsScopedToRunNotItem() {
        // The permission only ever governs an action taken against one specific build, never
        // the job/item as a whole - Run.PERMISSIONS/PermissionScope.RUN matches how Jenkins
        // core itself scopes equivalent per-build permissions (Run.DELETE, Run.UPDATE).
        assertEquals(Run.PERMISSIONS, ChangeInvestigatorPermissions.RUN_AI_ANALYSIS.group);
        // isContainedBy(RUN) is only true if RUN itself is one of the permission's declared
        // scopes (containment flows child -> parent, e.g. RUN -> ITEM, never the reverse) - an
        // ITEM-scoped permission would fail this assertion.
        assertTrue(
                ChangeInvestigatorPermissions.RUN_AI_ANALYSIS.isContainedBy(PermissionScope.RUN),
                "expected the permission to declare PermissionScope.RUN");
        assertTrue(
                ChangeInvestigatorPermissions.RUN_AI_ANALYSIS.isContainedBy(PermissionScope.ITEM),
                "RUN scope must still be contained by ITEM so the permission remains configurable "
                        + "in per-project/per-folder Matrix Authorization Strategy tables");
    }

    @Test
    void permissionHasAHumanReadableDescription() {
        assertNotNull(
                ChangeInvestigatorPermissions.RUN_AI_ANALYSIS.description,
                "the permission must have a description so Matrix Authorization Strategy and other "
                        + "permission UIs can explain what it allows");
    }
}
