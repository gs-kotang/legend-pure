// Copyright 2023 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.runtime.java.compiled.serialization.binary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.impl.utility.ArrayIterate;
import org.eclipse.collections.impl.utility.Iterate;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepository;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepositoryProviderHelper;
import org.finos.legend.pure.m4.serialization.Writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public class DistributedMetadataSpecification
{
    private final String name;
    private final Set<String> dependencies;

    private DistributedMetadataSpecification(String name, Set<String> dependencies)
    {
        this.name = name;
        this.dependencies = dependencies;
    }

    /**
     * Get the name of this metadata.
     *
     * @return metadata name
     */
    public String getName()
    {
        return this.name;
    }

    /**
     * Get the names of the metadata that this depends on.
     *
     * @return metadata dependency names
     */
    public Set<String> getDependencies()
    {
        return this.dependencies;
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof DistributedMetadataSpecification))
        {
            return false;
        }
        DistributedMetadataSpecification that = (DistributedMetadataSpecification) other;
        return this.name.equals(that.name) && this.dependencies.equals(that.dependencies);
    }

    @Override
    public int hashCode()
    {
        return this.name.hashCode();
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "{name=\"" + this.name + "\", dependencies=" + this.dependencies + "}";
    }

    public String writeSpecification(Path baseDirectory)
    {
        return writeSpecification(baseDirectory, this);
    }

    public String writeSpecification(JarOutputStream jarOutputStream)
    {
        return writeSpecification(jarOutputStream, this);
    }

    public String writeSpecification(FileWriter fileWriter)
    {
        return writeSpecification(fileWriter, this);
    }

    public static DistributedMetadataSpecification newSpecification(String name)
    {
        return new DistributedMetadataSpecification(DistributedMetadataHelper.validateMetadataName(name), Collections.emptySet());
    }

    public static DistributedMetadataSpecification newSpecification(String name, String... dependencies)
    {
        return newSpecification(name, Sets.immutable.with(dependencies).castToSet());
    }

    @JsonCreator
    public static DistributedMetadataSpecification newSpecification(@JsonProperty("name") String name, @JsonProperty("dependencies") Iterable<String> dependencies)
    {
        return (dependencies == null) ?
                newSpecification(name) :
                newSpecification(name, (dependencies instanceof Set) ? (Set<String>) dependencies : Sets.immutable.withAll(dependencies).castToSet());
    }

    private static DistributedMetadataSpecification newSpecification(String name, Set<String> dependencies)
    {
        if (dependencies.isEmpty())
        {
            return newSpecification(name);
        }

        MutableList<String> invalidDependencies = Iterate.reject(dependencies, DistributedMetadataHelper::isValidMetadataName, Lists.mutable.empty());
        if (invalidDependencies.notEmpty())
        {
            StringBuilder builder = new StringBuilder();
            invalidDependencies.sortThis(Comparator.nullsFirst(Comparator.naturalOrder())).forEach(d ->
            {
                builder.append((builder.length() == 0) ? ((invalidDependencies.size() == 1) ? "Invalid dependency: " : "Invalid dependencies: ") : ", ");
                if (d == null)
                {
                    builder.append("null");
                }
                else
                {
                    builder.append('"').append(d).append('"');
                }
            });
            throw new IllegalArgumentException(builder.toString());
        }
        return new DistributedMetadataSpecification(DistributedMetadataHelper.validateMetadataName(name), (dependencies instanceof ImmutableSet) ? dependencies : Collections.unmodifiableSet(dependencies));
    }

    // Writing specifications

    public static String writeSpecification(Path directory, DistributedMetadataSpecification metadata)
    {
        return writeSpecification(FileWriters.fromDirectory(directory), metadata);
    }

    public static List<String> writeSpecifications(Path directory, DistributedMetadataSpecification... metadata)
    {
        return writeSpecifications(FileWriters.fromDirectory(directory), metadata);
    }

    public static List<String> writeSpecifications(Path directory, Iterable<? extends DistributedMetadataSpecification> metadata)
    {
        return writeSpecifications(FileWriters.fromDirectory(directory), metadata);
    }

    public static String writeSpecification(JarOutputStream jarOutputStream, DistributedMetadataSpecification metadata)
    {
        return writeSpecification(FileWriters.fromJarOutputStream(jarOutputStream), metadata);
    }

    public static List<String> writeSpecifications(JarOutputStream jarOutputStream, DistributedMetadataSpecification... metadata)
    {
        return writeSpecifications(FileWriters.fromJarOutputStream(jarOutputStream), metadata);
    }

    public static List<String> writeSpecifications(JarOutputStream jarOutputStream, Iterable<? extends DistributedMetadataSpecification> metadata)
    {
        return writeSpecifications(FileWriters.fromJarOutputStream(jarOutputStream), metadata);
    }

    public static String writeSpecification(FileWriter fileWriter, DistributedMetadataSpecification metadata)
    {
        return writeSpecification(metadata, fileWriter, getSpecObjectWriter());
    }

    public static List<String> writeSpecifications(FileWriter fileWriter, DistributedMetadataSpecification... metadata)
    {
        ObjectWriter objectWriter = getSpecObjectWriter();
        return ArrayIterate.collect(metadata, m -> writeSpecification(m, fileWriter, objectWriter));
    }

    public static List<String> writeSpecifications(FileWriter fileWriter, Iterable<? extends DistributedMetadataSpecification> metadata)
    {
        ObjectWriter objectWriter = getSpecObjectWriter();
        return Iterate.collect(metadata, m -> writeSpecification(m, fileWriter, objectWriter), Lists.mutable.empty());
    }

    private static String writeSpecification(DistributedMetadataSpecification metadata, FileWriter fileWriter, ObjectWriter objectWriter)
    {
        byte[] bytes;
        try
        {
            bytes = objectWriter.writeValueAsBytes(metadata);
        }
        catch (JsonProcessingException e)
        {
            throw new RuntimeException("Error writing definition for " + metadata, e);
        }

        String filePath = DistributedMetadataHelper.getMetadataSpecificationFilePath(metadata.getName());
        try (Writer binaryWriter = fileWriter.getWriter(filePath))
        {
            binaryWriter.writeBytes(bytes);
        }
        return filePath;
    }

    // Reading specifications

    public static DistributedMetadataSpecification readSpecification(Path file) throws IOException
    {
        try (InputStream stream = Files.newInputStream(file))
        {
            return readSpecification(stream);
        }
    }

    public static DistributedMetadataSpecification readSpecification(InputStream stream) throws IOException
    {
        ObjectReader objectReader = getSpecObjectReader();
        return objectReader.readValue(stream);
    }

    public static DistributedMetadataSpecification readSpecification(Reader reader) throws IOException
    {
        ObjectReader objectReader = getSpecObjectReader();
        return objectReader.readValue(reader);
    }

    // Loading specifications

    /**
     * Load the named metadata specifications, plus any dependencies. An exception will be thrown if any of the
     * specifications or dependencies cannot be found.
     *
     * @param classLoader   class loader to load from
     * @param metadataNames names of metadata specifications to load
     * @return metadata specifications
     */
    public static List<DistributedMetadataSpecification> loadSpecifications(ClassLoader classLoader, String... metadataNames)
    {
        return loadSpecifications(classLoader, Arrays.asList(metadataNames));
    }

    /**
     * Load the named metadata specifications, plus any dependencies. An exception will be thrown if any of the
     * specifications or dependencies cannot be found.
     *
     * @param classLoader   class loader to load from
     * @param metadataNames names of metadata specifications to load
     * @return metadata specifications
     */
    public static List<DistributedMetadataSpecification> loadSpecifications(ClassLoader classLoader, Iterable<String> metadataNames)
    {
        MutableMap<String, Set<String>> dependenciesByRepo = Maps.mutable.empty();
        try
        {
            Set<String> visited = Sets.mutable.empty();
            List<DistributedMetadataSpecification> metadataList = Lists.mutable.empty();
            Deque<String> toLoad = Iterate.addAllTo(metadataNames, new ArrayDeque<>());
            ObjectReader reader = getSpecObjectReader();
            while (!toLoad.isEmpty())
            {
                String name = toLoad.removeLast();
                if (visited.add(name))
                {
                    DistributedMetadataSpecification metadata = loadSpecFromClassLoader(classLoader, name, reader);
                    metadataList.add(metadata);
                    dependenciesByRepo.put(name, metadata.getDependencies());
                    toLoad.addAll(metadata.getDependencies());
                }
            }
            return metadataList;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e.getMessage() +
                    "\nDirectly asked for:" + metadataNames +
                    "\nLoaded up to now (with dependencies):" + dependenciesByRepo +
                    "\nThe requested repos are coming from PAR projects scanning. You may not have included the project containing the distributed metadata for Java generation.", e);
        }
    }

    /**
     * Load all metadata specifications in the given class loader. Note that this does not ensure that all dependencies
     * are present.
     *
     * @param classLoader class loader to load from
     * @return all available metadata specifications
     */
    public static List<DistributedMetadataSpecification> loadAllSpecifications(ClassLoader classLoader)
    {
        String directoryName = DistributedMetadataHelper.getMetadataSpecificationsDirectory();
        Enumeration<URL> urls;
        try
        {
            urls = classLoader.getResources(directoryName);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Error loading " + directoryName, e);
        }

        if (!urls.hasMoreElements())
        {
            return Collections.emptyList();
        }

        ObjectReader reader = getSpecObjectReader();
        Map<String, DistributedMetadataSpecification> index = Maps.mutable.empty();
        while (urls.hasMoreElements())
        {
            loadSpecsFromUrl(urls.nextElement(), reader, m ->
            {
                DistributedMetadataSpecification current = index.put(m.getName(), m);
                if ((current != null) && !current.equals(m))
                {
                    throw new RuntimeException("Conflicting specifications for metadata \"" + m.getName() + "\": " + current + " vs " + m);
                }
            });
        }
        return Lists.mutable.withAll(index.values());
    }

    private static void loadSpecsFromUrl(URL url, ObjectReader objectReader, Consumer<DistributedMetadataSpecification> consumer)
    {
        if ("file".equalsIgnoreCase(url.getProtocol()))
        {
            loadSpecsFromFileUrl(url, objectReader, consumer);
        }
        else if ("jar".equalsIgnoreCase(url.getProtocol()))
        {
            loadSpecsFromJarUrl(url, objectReader, consumer);
        }
    }

    private static void loadSpecsFromFileUrl(URL fileUrl, ObjectReader reader, Consumer<DistributedMetadataSpecification> consumer)
    {
        try
        {
            Path path = Paths.get(fileUrl.toURI());
            try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(path))
            {
                for (Path filePath : dirStream)
                {
                    if (DistributedMetadataHelper.isMetadataSpecificationFileName(filePath.getFileName().toString()))
                    {
                        DistributedMetadataSpecification metadata;
                        try (InputStream stream = Files.newInputStream(filePath))
                        {
                            metadata = reader.readValue(stream);
                        }
                        consumer.accept(metadata);
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Error loading metadata from " + fileUrl, e);
        }
    }

    private static void loadSpecsFromJarUrl(URL jarUrl, ObjectReader reader, Consumer<DistributedMetadataSpecification> consumer)
    {
        try
        {
            JarURLConnection connection = (JarURLConnection) jarUrl.openConnection();
            try (JarFile jarFile = connection.getJarFile())
            {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements())
                {
                    JarEntry entry = entries.nextElement();
                    if (DistributedMetadataHelper.isMetadataSpecificationFilePath(entry.getName()))
                    {
                        DistributedMetadataSpecification metadata;
                        try (InputStream stream = jarFile.getInputStream(entry))
                        {
                            metadata = reader.readValue(stream);
                        }
                        consumer.accept(metadata);
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Error loading metadata from " + jarUrl, e);
        }
    }

    private static DistributedMetadataSpecification loadSpecFromClassLoader(ClassLoader classLoader, String metadataName, ObjectReader reader)
    {
        String resourceName = DistributedMetadataHelper.getMetadataSpecificationFilePath(metadataName);
        Enumeration<URL> urls;
        try
        {
            urls = classLoader.getResources(resourceName);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Error loading " + resourceName, e);
        }

        if (!urls.hasMoreElements())
        {
            throw new RuntimeException("Cannot find metadata \"" + metadataName + "\" (resource name \"" + resourceName + "\")");
        }

        // Load metadata
        URL url = urls.nextElement();
        DistributedMetadataSpecification metadata;
        try
        {
            metadata = reader.readValue(url);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Error reading definition of metadata \"" + metadataName + "\" from " + url, e);
        }

        // Check for other possibly conflicting definitions
        while (urls.hasMoreElements())
        {
            URL otherUrl = urls.nextElement();
            DistributedMetadataSpecification otherMetadata;
            try
            {
                otherMetadata = reader.readValue(otherUrl);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException("Error reading definition of metadata \"" + metadataName + "\" from " + otherUrl + "\nFound Repositories on the ClassPath: " + CodeRepositoryProviderHelper.findCodeRepositories().collect(CodeRepository::getName), e);
            }
            if (!metadata.equals(otherMetadata))
            {
                throw new RuntimeException("Conflicting definitions of metadata \"" + metadataName + "\": " + metadata + " (from " + url + ") vs " + otherMetadata + " (from " + otherUrl + ")");
            }
        }

        return metadata;
    }

    // Readers and writers

    private static ObjectReader getSpecObjectReader()
    {
        return JsonMapper.builder()
                .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
                .build()
                .readerFor(DistributedMetadataSpecification.class);
    }

    private static ObjectWriter getSpecObjectWriter()
    {
        return JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .enable(StreamWriteFeature.FLUSH_PASSED_TO_STREAM)
                .build()
                .writerFor(DistributedMetadataSpecification.class);
    }
}
