package com.tngtech.archunit.core.importer;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.tngtech.archunit.Internal;

interface ClassFileSource extends Iterable<ClassFileLocation> {
    @Internal
    class FromFilePath extends SimpleFileVisitor<Path> implements ClassFileSource {
        private static final PathMatcher classMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.class");
        private final Set<ClassFileLocation> classFileLocations = new HashSet<>();
        private final ImportOptions importOptions;

        FromFilePath(Path path, ImportOptions importOptions) {
            this.importOptions = importOptions;
            if (path.toFile().exists()) {
                try {
                    Files.walkFileTree(path, this);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public Iterator<ClassFileLocation> iterator() {
            return classFileLocations.iterator();
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (classMatcher.matches(file) && importOptions.include(Location.of(file))) {
                classFileLocations.add(new InputStreamSupplierClassFileLocation(file.toUri(), newInputStreamSupplierFor(file)));
            }
            return super.visitFile(file, attrs);
        }

        private Supplier<InputStream> newInputStreamSupplierFor(final Path file) {
            return new InputStreamSupplier() {
                @Override
                InputStream getInputStream() throws IOException {
                    return Files.newInputStream(file);
                }
            };
        }
    }

    @Internal
    class FromJar implements ClassFileSource {
        private final FluentIterable<ClassFileLocation> classFileLocations;

        FromJar(JarURLConnection connection, ImportOptions importOptions) {
            try {
                JarFile jarFile = connection.getJarFile();
                String prefix = connection.getJarEntry() != null ? connection.getJarEntry().getName() : "";
                classFileLocations = FluentIterable.from(Collections.list(jarFile.entries()))
                        .filter(classFilesBeneath(prefix))
                        .transform(toClassFilesInJarOf(connection))
                        .filter(by(importOptions))
                        .transform(toInputStreamSupplier());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private Predicate<JarEntry> classFilesBeneath(final String prefix) {
            return new Predicate<JarEntry>() {
                @Override
                public boolean apply(JarEntry input) {
                    return input.getName().startsWith(prefix)
                            && input.getName().endsWith(".class");
                }
            };
        }

        private Function<JarEntry, ClassFileInJar> toClassFilesInJarOf(final JarURLConnection connection) {
            return new Function<JarEntry, ClassFileInJar>() {
                @Override
                public ClassFileInJar apply(JarEntry input) {
                    return new ClassFileInJar(connection, input);
                }
            };
        }

        private Predicate<ClassFileInJar> by(final ImportOptions importOptions) {
            return new Predicate<ClassFileInJar>() {
                @Override
                public boolean apply(ClassFileInJar input) {
                    return input.isIncludedIn(importOptions);
                }
            };
        }

        private Function<ClassFileInJar, ClassFileLocation> toInputStreamSupplier() {
            return new Function<ClassFileInJar, ClassFileLocation>() {
                @Override
                public ClassFileLocation apply(final ClassFileInJar input) {
                    return new InputStreamSupplierClassFileLocation(input.getUri(), new InputStreamSupplier() {
                        @Override
                        InputStream getInputStream() throws IOException {
                            return input.openStream();
                        }
                    });
                }
            };
        }

        @Override
        public Iterator<ClassFileLocation> iterator() {
            return classFileLocations.iterator();
        }

        private static class ClassFileInJar {
            private final JarURLConnection connection;
            private final JarEntry jarEntry;
            private final URI uri;

            private ClassFileInJar(JarURLConnection connection, JarEntry jarEntry) {
                this.connection = connection;
                this.jarEntry = jarEntry;
                this.uri = makeJarUri(jarEntry);
            }

            private URI makeJarUri(JarEntry input) {
                return Location.of(connection.getJarFileURL()).append(input.getName()).asURI();
            }

            URI getUri() {
                return uri;
            }

            InputStream openStream() throws IOException {
                return connection.getJarFile().getInputStream(jarEntry);
            }

            boolean isIncludedIn(ImportOptions importOptions) {
                return importOptions.include(Location.of(uri));
            }
        }
    }

    @Internal
    class InputStreamSupplierClassFileLocation implements ClassFileLocation {
        private final URI uri;
        private final Supplier<InputStream> streamSupplier;

        InputStreamSupplierClassFileLocation(URI uri, Supplier<InputStream> streamSupplier) {
            this.uri = uri;
            this.streamSupplier = streamSupplier;
        }

        @Override
        public InputStream openStream() {
            return streamSupplier.get();
        }

        @Override
        public URI getUri() {
            return uri;
        }
    }

    @Internal
    abstract class InputStreamSupplier implements Supplier<InputStream> {
        @Override
        public InputStream get() {
            try {
                return getInputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        abstract InputStream getInputStream() throws IOException;
    }
}
