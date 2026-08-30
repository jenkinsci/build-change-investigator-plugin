package io.jenkins.plugins.changeinvestigator.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Confirms the plugin's custom permission composes correctly into Jenkins' job permission
 * matrix: a user with only read/build access does not get it, a user explicitly granted it
 * (or granted the implying {@code Item.BUILD}) does.
 */
@WithJenkins
class ChangeInvestigatorPermissionsTest {

    @Test
    void onlyUsersGrantedThePermissionCanRunAnalysis(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("readOnlyUser")
                .grant(Jenkins.READ, Item.READ, ChangeInvestigatorPermissions.RUN_AI_ANALYSIS)
                        .everywhere().to("investigatorUser"));

        FreeStyleProject project = jenkins.createFreeStyleProject("permission-test");

        try (ACLContext ctx = ACL.as2(User.getById("readOnlyUser", true).impersonate2())) {
            assertFalse(project.hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS));
        }

        try (ACLContext ctx = ACL.as2(User.getById("investigatorUser", true).impersonate2())) {
            assertTrue(project.hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS));
        }
    }

    @Test
    void permissionIsImpliedByBuildPermission(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("builderUser"));

        FreeStyleProject project = jenkins.createFreeStyleProject("implied-permission-test");

        try (ACLContext ctx = ACL.as2(User.getById("builderUser", true).impersonate2())) {
            assertTrue(project.hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS),
                    "Item.BUILD should imply the plugin's RUN_AI_ANALYSIS permission");
        }
    }
}
