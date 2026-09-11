package io.jenkins.plugins.changeinvestigator.notification.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.changeinvestigator.notification.event.SafeContent;
import org.junit.jupiter.api.Test;

class NotificationNavigationTest {
    @Test
    void onlyRelativeApprovedNavigationIsAllowed() {
        String root = "https://jenkins.example.test/ci/";
        assertEquals(root + "job/demo/1/", SafeContent.navigation(root, "job/demo/1/"));
        for (String value : new String[] {
            "../private",
            "%2e%2e/private",
            "%252e%252e/private",
            "job/../../private",
            "//elsewhere.example.test/",
            "http://jenkins.example.test/ci/job/demo/",
            "job/demo/#secret",
            "job/demo/?token=secret",
            "job/%5cprivate",
            "job/%00private",
            "https://jenkins.example.test/ci/job/demo/"
        }) {
            assertNull(SafeContent.navigation(root, value), value);
        }
        assertNull(SafeContent.navigation("https://jenkins.example.test/ci", "job/demo/"));
    }
}
