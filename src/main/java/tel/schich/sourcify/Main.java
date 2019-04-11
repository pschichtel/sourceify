/*
 * Sourceify - A tool to decompile an entire local maven repository to provide source jars
 * Copyright © 2019 Phillip Schichtel (phillip@schich.tel)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package tel.schich.sourcify;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import tel.schich.sourcify.Main.MavenArtifact.Kind;

import static java.nio.file.Files.newOutputStream;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.Comparator.reverseOrder;

public class Main {
    private static final String SOURCES_SUFFIX = "sources";
    private static final String JAVADOC_SUFFIX = "javadoc";

    public static final String SOURCEIFY_MARKER_FILENAME = "sourceify-decompiled";

    public static void main(String[] args) throws IOException {

        if (args.length != 2) {
            System.err.println("Usage: <maven repo root> <decompiler>");
            System.exit(1);
        }

        Path base = Paths.get(args[0]);
        if (!Files.isDirectory(base)) {
            System.err.println("The given root is not a directory!");
            System.exit(2);
        }

        Path decompiler = Paths.get(args[1]);
        if (!Files.isExecutable(decompiler)) {
            System.err.println("The given decompiler is not executable!");
            System.exit(3);
        }

        Stream<MavenArtifact> artifacts = Files.walk(base)
                .parallel()
                .filter(p -> !Files.isDirectory(p))
                .flatMap(p -> toMavenArtifact(base, p).map(Stream::of).orElseGet(Stream::empty))
                .filter(a -> !a.isSnapshot() && a.getKind() == Kind.CODE);

        artifacts.forEach(p -> processArtifact(p, decompiler));
    }

    private static Optional<MavenArtifact> toMavenArtifact(Path base, Path file) {
        String extension = getExtension(file);
        if (!extension.equalsIgnoreCase("jar")) {
            return Optional.empty();
        }

        String artifactFileName = file.getFileName().toString();

        Path versionDir = file.getParent();
        String version = versionDir.getFileName().toString();

        Path artifactDir = versionDir.getParent();
        String artifactId = artifactDir.getFileName().toString();

        Path groupDir = artifactDir.getParent();
        Path relativeGroupDir = base.relativize(groupDir);
        String groupId = joinSegments(relativeGroupDir, ".");

        String filenamePrefix = artifactId + "-" + version + "-";
        int filenameLength = artifactFileName.length();
        int filenamePrefixLength = filenamePrefix.length();
        int filenameSuffixLength = extension.length() + 1;

        final String filenameRest;
        if (filenamePrefixLength + filenameSuffixLength >= filenameLength) {
            filenameRest = "";
        } else {
            filenameRest = artifactFileName.substring(filenamePrefixLength, filenameLength - filenameSuffixLength);
        }

        final String build;
        final Kind kind;
        if (filenameRest.endsWith("-" + SOURCES_SUFFIX)) {
            kind = Kind.SOURCE;
            build = filenameRest.substring(0, filenameRest.length() - (SOURCES_SUFFIX.length() + 1));
        } else if (filenameRest.endsWith("-" + JAVADOC_SUFFIX)) {
            kind = Kind.JAVADOC;
            build = filenameRest.substring(0, filenameRest.length() - (JAVADOC_SUFFIX.length() + 1));
        } else if (filenameRest.equals(SOURCES_SUFFIX)) {
            kind = Kind.SOURCE;
            build = null;
        } else if (filenameRest.equals(JAVADOC_SUFFIX)) {
            kind = Kind.JAVADOC;
            build = null;
        } else {
            kind = Kind.CODE;
            build = filenameRest.isEmpty() ? null : filenameRest;
        }

        return Optional.of(new MavenArtifact(groupId, artifactId, version, build, file, kind));
    }

    private static void processArtifact(MavenArtifact artifact, Path decompiler) {
        MavenArtifact sourceArtifact = artifact.getRelated(Kind.SOURCE);
        Path sourcePath = sourceArtifact.getPath();
        if (Files.exists(sourcePath)) {
            System.out.println(sourcePath + " already exists!");
            return;
        }


        System.out.println("Processing: " + artifact.toString());
        try {
            generateSourceJar(artifact, sourceArtifact, decompiler);
        } catch (IOException e) {
            System.err.println("Failed to generate the source.jar!");
            e.printStackTrace(System.err);
        }
    }

    private static void generateSourceJar(MavenArtifact code, MavenArtifact source, Path decompiler) throws IOException {
        Path decompilationTarget = Files.createTempDirectory("sourceify-decompile-");
        try {
            Path codePath = code.getPath();
            if (!runDecompiler(codePath, decompilationTarget, decompiler)) {
                System.err.println("Decompiler failed!");
                return;
            }

            Path sourcePath = source.getPath();
            Path possibleJar = decompilationTarget.resolve(codePath.getFileName());
            Path zipTarget = sourcePath.getParent().resolve("." + sourcePath.getFileName() + ".tmp");

            if (Files.exists(possibleJar)) {
                addMarkerToExistingJar(possibleJar);
                Files.move(possibleJar, zipTarget);
            } else {
                zipTree(decompilationTarget, zipTarget);
            }

            Files.move(zipTarget, sourcePath, ATOMIC_MOVE);
        } finally {
            deleteTree(decompilationTarget);
        }
    }

    private static void addMarkerToExistingJar(Path zipPath) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(zipPath, null)) {
            Path markerPath = fs.getPath(SOURCEIFY_MARKER_FILENAME);
            try (OutputStream out = newOutputStream(markerPath, CREATE, TRUNCATE_EXISTING)) {
                writeMarkerContent(out);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walk(root)
                .sorted(reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    private static boolean runDecompiler(Path jar, Path target, Path decompiler) throws IOException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.inheritIO();
        builder.command(decompiler.toAbsolutePath().toString(), jar.toAbsolutePath().toString(), target.toAbsolutePath().toString());
        Process proc = builder.start();
        try {
            return proc.waitFor() == 0;
        } catch (InterruptedException e) {
            System.err.println("Decompiler execution got interrupted!");
            e.printStackTrace(System.err);
        }
        return false;
    }

    private static void zipTree(Path source, Path target) throws IOException {
        try (ZipOutputStream sourceJarOutput = new ZipOutputStream(newOutputStream(target, CREATE, TRUNCATE_EXISTING))) {
            writeMarkerEntry(sourceJarOutput);
            byte[] buffer = new byte[8196];
            List<Path> sourceFiles = Files.walk(source).filter(p -> !Files.isDirectory(p)).collect(Collectors.toList());
            for (Path p : sourceFiles) {
                writeZipEntry(source, p, sourceJarOutput, buffer);
            }
        }
    }

    private static void writeMarkerEntry(ZipOutputStream out) throws IOException {
        ZipEntry e = new ZipEntry(SOURCEIFY_MARKER_FILENAME);
        out.putNextEntry(e);
        try {
            writeMarkerContent(out);
        } finally {
            out.closeEntry();
        }
    }

    private static void writeMarkerContent(OutputStream out) throws IOException {
        String message = "Generated by Sourceify!\n";
        out.write(message.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeZipEntry(Path base, Path p, ZipOutputStream out, byte[] buffer) throws IOException {
        Path relative = base.relativize(p);

        InputStream input = Files.newInputStream(p);

        ZipEntry entry = new ZipEntry(joinSegments(relative, "/"));
        out.putNextEntry(entry);
        try {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } finally {
            out.closeEntry();
        }
    }

    public static String getExtension(Path p) {
        String s = p.getFileName().toString();
        int i = s.lastIndexOf('.');
        if (i == -1) {
            return "";
        } else {
            return s.substring(i + 1);
        }
    }

    public static String joinSegments(Path p, String delimitor) {
        return StreamSupport.stream(p.spliterator(), false)
                .map(Path::toString)
                .collect(Collectors.joining(delimitor));
    }

    public static final class MavenArtifact {

        private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

        public enum Kind {
            CODE,
            SOURCE,
            JAVADOC
        }

        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String build;
        private final Path path;
        private final Kind kind;

        public MavenArtifact(String groupId, String artifactId, String version, String build, Path path, Kind kind) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.build = build;
            this.path = path;
            this.kind = kind;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }

        public Optional<String> getBuild() {
            return Optional.ofNullable(build);
        }

        public Path getPath() {
            return path;
        }

        public Kind getKind() {
            return kind;
        }

        public boolean isSnapshot() {
            return version.endsWith(SNAPSHOT_SUFFIX);
        }

        public MavenArtifact getRelated(Kind kind) {
            if (this.kind == kind) {
                return this;
            }
            
            final String kindSuffix;
            switch (kind) {
            case SOURCE:
                kindSuffix = "-" + SOURCES_SUFFIX;
                break;
            case JAVADOC:
                kindSuffix = "-" + JAVADOC_SUFFIX;
                break;
            default:
                kindSuffix = "";
                break;
            }

            String build = this.build == null ? "" : "-" + this.build;
            String extension = getExtension(path);
            String newFilename = artifactId + "-" + version + build + kindSuffix + "." + extension;

            Path newPath = path.getParent().resolve(newFilename);

            return new MavenArtifact(groupId, artifactId, version, build, newPath, kind);
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            out.append(groupId).append(':').append(artifactId).append(':').append(version);
            if (build != null) {
                out.append(':').append(build);
            }

            out.append(':').append(kind);
            return out.toString();
        }
    }
}
