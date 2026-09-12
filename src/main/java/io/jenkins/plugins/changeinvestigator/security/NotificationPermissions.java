package io.jenkins.plugins.changeinvestigator.security;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;

/** Independent item-scoped grants for authenticated human investigation actions. */
public final class NotificationPermissions {
    public static final PermissionGroup GROUP =
            new PermissionGroup(NotificationPermissions.class, Messages._NotificationPermissions_GroupName());
    public static final Permission TRIAGE = new Permission(
            GROUP,
            "Triage",
            Messages._NotificationPermissions_TriageDescription(),
            Jenkins.ADMINISTER,
            PermissionScope.ITEM);
    public static final Permission CONFIRM = new Permission(
            GROUP,
            "Confirm",
            Messages._NotificationPermissions_ConfirmDescription(),
            Jenkins.ADMINISTER,
            PermissionScope.ITEM);
    public static final Permission MUTE = new Permission(
            GROUP,
            "Mute",
            Messages._NotificationPermissions_MuteDescription(),
            Jenkins.ADMINISTER,
            PermissionScope.ITEM);

    private NotificationPermissions() {}

    @Initializer(after = InitMilestone.PLUGINS_PREPARED)
    public static void initialize() {
        /* Loading this class registers its stable permission IDs. */
    }
}
