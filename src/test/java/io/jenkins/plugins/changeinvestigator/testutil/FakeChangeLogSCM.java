package io.jenkins.plugins.changeinvestigator.testutil;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.scm.ChangeLogParser;
import hudson.scm.ChangeLogSet;
import hudson.scm.RepositoryBrowser;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A minimal, in-memory-configured fake SCM used only in tests, so evidence-collection logic
 * can be exercised against a real {@link hudson.model.FreeStyleBuild} without depending on the
 * Git plugin or an external SCM. Each instance is configured with a fixed list of "commits" it
 * reports as its changelog for the build it is attached to.
 */
public class FakeChangeLogSCM extends SCM implements Serializable {

    private static final long serialVersionUID = 1L;

    /** One fake commit: id, author, message, affected files. */
    public record FakeCommit(String id, String author, String message, List<String> files) implements Serializable {
    }

    private final List<FakeCommit> commits;

    public FakeChangeLogSCM(List<FakeCommit> commits) {
        this.commits = commits;
    }

    public static FakeChangeLogSCM none() {
        return new FakeChangeLogSCM(List.of());
    }

    @Override
    public void checkout(Run<?, ?> build, Launcher launcher, FilePath workspace, TaskListener listener,
                          File changelogFile, hudson.scm.SCMRevisionState baseline) throws IOException {
        if (changelogFile == null) {
            return;
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(changelogFile))) {
            for (FakeCommit c : commits) {
                w.write(c.id() + "\t" + c.author() + "\t" + String.join(",", c.files()) + "\t" + c.message());
                w.newLine();
            }
        }
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new ChangeLogParser() {
            @Override
            public ChangeLogSet<? extends ChangeLogSet.Entry> parse(Run build, RepositoryBrowser<?> browser,
                                                                      File changelogFile) throws IOException {
                List<FakeEntry> entries = new ArrayList<>();
                if (changelogFile.exists()) {
                    try (BufferedReader r = new BufferedReader(new FileReader(changelogFile))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            String[] parts = line.split("\t", 4);
                            List<String> files = parts[2].isEmpty()
                                    ? List.of() : List.of(parts[2].split(","));
                            entries.add(new FakeEntry(parts[0], parts[1], parts[3], files));
                        }
                    }
                }
                return new FakeChangeLogSet(build, entries);
            }
        };
    }

    @Override
    public SCMDescriptor<?> getDescriptor() {
        return DESCRIPTOR;
    }

    private static final SCMDescriptor<FakeChangeLogSCM> DESCRIPTOR =
            new SCMDescriptor<>(FakeChangeLogSCM.class, null) {
                @Override
                public String getDisplayName() {
                    return "Fake (test only)";
                }
            };

    public static final class FakeChangeLogSet extends ChangeLogSet<FakeEntry> {

        private final List<FakeEntry> entries;

        protected FakeChangeLogSet(Run<?, ?> run, List<FakeEntry> entries) {
            super(run, null);
            this.entries = entries;
            for (FakeEntry e : entries) {
                e.attachTo(this);
            }
        }

        @Override
        public boolean isEmptySet() {
            return entries.isEmpty();
        }

        @Override
        public Iterator<FakeEntry> iterator() {
            return Collections.unmodifiableList(entries).iterator();
        }
    }

    public static final class FakeEntry extends ChangeLogSet.Entry {
        private final String id;
        private final String author;
        private final String msg;
        private final List<String> files;

        public FakeEntry(String id, String author, String msg, List<String> files) {
            this.id = id;
            this.author = author;
            this.msg = msg;
            this.files = files;
        }

        @SuppressWarnings("rawtypes")
        void attachTo(ChangeLogSet parent) {
            setParent(parent);
        }

        @Override
        public String getCommitId() {
            return id;
        }

        @Override
        public String getMsg() {
            return msg;
        }

        @Override
        public User getAuthor() {
            return User.getById(author, true);
        }

        @Override
        public Collection<String> getAffectedPaths() {
            return files;
        }

        @Override
        public long getTimestamp() {
            return 0L;
        }
    }
}
