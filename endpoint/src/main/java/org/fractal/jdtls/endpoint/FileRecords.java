// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * The endpoint's own record of a worktree's files (design §11 "Keeping in step with the disk"): each file's
 * modification time, status-change time (nanoseconds), size and inode. Eclipse's refresh compares only a file's
 * millisecond modification time with the one it stored, so a change that keeps it ({@code cp -p}, {@code rsync -a},
 * {@code tar x}, {@code touch -r}, two writes within one millisecond) is invisible to it. Every such change still
 * moves the status-change time, which nothing but the kernel sets, so comparing two records finds it.
 *
 * <p>Plain Java, with no Eclipse class: the fixed point ({@link DiskSync}) decides what Eclipse does with it.
 */
final class FileRecords {

    private FileRecords() {}

    /** One file's state. {@code inode} is whatever the file system's {@code unix:ino} is. */
    record State(long mtimeNs, long ctimeNs, long size, Object inode) {
        long mtimeMs() {
            return Math.floorDiv(mtimeNs, 1_000_000L);
        }
    }

    /** What two records differ in: the files added, removed, and changed, and which changes kept the mtime. */
    record Diff(Set<Path> added, Set<Path> removed, Set<Path> changed, Set<Path> keptMtime) {
        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        /** Whether every difference is a change that kept its millisecond modification time. */
        boolean onlyKeptMtime() {
            return !isEmpty() && added.isEmpty() && removed.isEmpty() && keptMtime.equals(changed);
        }

        /** The files a refresh might not take in: every file new or changed. */
        Set<Path> candidates() {
            Set<Path> all = new TreeSet<>(added);
            all.addAll(changed);
            return all;
        }
    }

    /**
     * The files under the folders, each folder walked once (one inside another is skipped), without following links.
     * Skipped, as Eclipse's view of a worktree skips or derives them: every {@code .git} (the endpoint's resource
     * filter hides them), Maven's build output, a {@code target} folder beside a {@code pom.xml}, and the endpoint's
     * own operation folders, the worktree {@code root}'s {@code temp/fractal-jdtls} (design §11 "Operation folders").
     * A file that vanishes during the walk is left out.
     */
    static Map<Path, State> scan(Path root, Collection<Path> folders) throws IOException {
        Map<Path, State> files = new HashMap<>();
        for (Path folder : outermost(folders)) {
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return skipped(dir, root) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !".git".equals(file.getFileName().toString())) {
                        State s = state(file);
                        if (s != null) {
                            files.put(file, s);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return files;
    }

    /**
     * Whether the walk skips this folder: {@code .git}, a {@code target} beside a {@code pom.xml}, or the worktree
     * {@code root}'s own {@code temp/fractal-jdtls}, the endpoint's operation folders, and no other folder of that name
     * (T002.10's review, R1). {@code root} is null for a folder of no tracked worktree.
     */
    static boolean skipped(Path dir, Path root) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return name.equals(".git") || name.equals("target") && dir.getParent() != null && Files.isRegularFile(dir.getParent().resolve("pom.xml"))
                || root != null && dir.equals(root.resolve(OperationFolder.TEMP).resolve(OperationFolder.NAME));
    }

    /** One file's state, or null if it can't be read (it was deleted meanwhile). */
    static State state(Path file) {
        try {
            Map<String, Object> a = Files.readAttributes(file, "unix:lastModifiedTime,ctime,size,ino", LinkOption.NOFOLLOW_LINKS);
            return new State(((FileTime) a.get("lastModifiedTime")).to(TimeUnit.NANOSECONDS), ((FileTime) a.get("ctime")).to(TimeUnit.NANOSECONDS),
                    (Long) a.get("size"), a.get("ino"));
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    static Diff diff(Map<Path, State> before, Map<Path, State> now) {
        Set<Path> added = new TreeSet<>();
        Set<Path> removed = new TreeSet<>();
        Set<Path> changed = new TreeSet<>();
        Set<Path> kept = new TreeSet<>();
        for (Map.Entry<Path, State> e : now.entrySet()) {
            State old = before.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey());
                if (old.mtimeMs() == e.getValue().mtimeMs()) {
                    kept.add(e.getKey());
                }
            }
        }
        for (Path p : before.keySet()) {
            if (!now.containsKey(p)) {
                removed.add(p);
            }
        }
        return new Diff(added, removed, changed, kept);
    }

    /** The folders with every one inside another removed. */
    static List<Path> outermost(Collection<Path> folders) {
        List<Path> sorted = new ArrayList<>(new TreeSet<>(folders.stream().map(Path::normalize).filter(Objects::nonNull).toList()));
        List<Path> result = new ArrayList<>();
        for (Path p : sorted) {
            if (result.stream().noneMatch(p::startsWith)) {
                result.add(p);
            }
        }
        return result;
    }
}
