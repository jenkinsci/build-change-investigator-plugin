package io.jenkins.plugins.changeinvestigator.slack.config;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.User;
import hudson.security.ACL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class ResponderMappingStorageTest {
    @Test
    void boundedCrudKeepsStableIdsAndSupportsMoreThanOneHundredMappings(JenkinsRule jenkins) {
        SlackConfiguration config = SlackConfiguration.get();
        List<ResponderMapping> mappings = new ArrayList<>();
        for (int i = 0; i < 150; i++)
            mappings.add(new ResponderMapping("Engineer " + i, "U" + String.format("%08d", i)));
        config.setResponderMappings(mappings);
        config.addMapping("  Alex Morrison  ", " U12345678 ");
        ResponderMapping added = config.getResponderMappings().get(150);
        String id = added.getId();
        assertEquals("Alex Morrison", added.getIdentity());
        assertEquals(151, config.getResponderMappings().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.getResponderMappings().clear());
        config.updateMapping(id, "Alex Updated", "U87654321");
        assertEquals(id, config.getResponderMappings().get(150).getId());
        config.load();
        assertEquals(id, config.getResponderMappings().get(150).getId());
        config.setTagResponders(true);
        assertNull(config.mappedSlackUser("  alex updated ", "T12345678"));
        config.removeMapping(id);
        assertEquals(150, config.getResponderMappings().size());
        assertNull(config.mappedSlackUser("Alex Updated"));
    }

    @Test
    void newEntriesRejectDuplicatesInvalidIdsAndOverflowWithoutLosingExistingData(JenkinsRule jenkins) {
        SlackConfiguration config = SlackConfiguration.get();
        config.addMapping("Alex Morrison", "U12345678");
        assertThrows(IllegalArgumentException.class, () -> config.addMapping(" alex MORRISON ", "U87654321"));
        assertThrows(IllegalArgumentException.class, () -> config.addMapping("Other", "<@U12345678>"));
        assertThrows(IllegalArgumentException.class, () -> config.addMapping("x".repeat(257), "U87654321"));
        assertThrows(IllegalArgumentException.class, () -> config.removeMapping("not-a-record"));
        assertEquals(1, config.getResponderMappings().size());
        List<ResponderMapping> full = new ArrayList<>();
        for (int i = 0; i < SlackConfiguration.MAX_RESPONDER_MAPPINGS; i++)
            full.add(new ResponderMapping("Engineer " + i, "U" + String.format("%08d", i)));
        config.setResponderMappings(full);
        assertThrows(IllegalArgumentException.class, () -> config.addMapping("Additional", "U87654321"));
        assertEquals(
                SlackConfiguration.MAX_RESPONDER_MAPPINGS,
                config.getResponderMappings().size());
    }

    @Test
    void legacyMappingsReceiveIdsThatSurviveTheNextLoad(JenkinsRule jenkins) throws Exception {
        SlackConfiguration config = SlackConfiguration.get();
        config.addMapping("Legacy Engineer", "U12345678");
        var file = jenkins.jenkins.getRootDir().toPath().resolve(SlackConfiguration.class.getName() + ".xml");
        String xml = Files.readString(file).replaceAll("<id>[^<]+</id>", "");
        Files.writeString(file, xml);
        SlackConfiguration migrated = new SlackConfiguration();
        String id = migrated.getResponderMappings().get(0).getId();
        assertNotNull(id);
        assertEquals("Legacy Engineer", migrated.getResponderMappings().get(0).getIdentity());
        assertEquals("U12345678", migrated.getResponderMappings().get(0).getSlackUser());
        assertEquals(id, new SlackConfiguration().getResponderMappings().get(0).getId());
    }

    @Test
    void mappingWritesRequireAdministration(JenkinsRule jenkins) {
        SlackConfiguration config = SlackConfiguration.get();
        config.addMapping("Protected", "U12345678");
        String id = config.getResponderMappings().get(0).getId();
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));
        try (var ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> config.addMapping("Other", "U87654321"));
            assertThrows(AccessDeniedException.class, () -> config.updateMapping(id, "Other", "U87654321"));
            assertThrows(AccessDeniedException.class, () -> config.removeMapping(id));
        }
        assertEquals(1, config.getResponderMappings().size());
    }
}
