package com.ai.southernquiet.cache.driver;

import com.ai.southernquiet.cache.Cache;
import com.ai.southernquiet.filesystem.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于 {@link FileSystem} 的缓存驱动.
 */
@Component("DEFAULT_CACHE_DRIVER")
public class FileSystemCache implements Cache {
    public final static String DEFAULT_ROOT = "CACHE";
    public final static String NAME_SEPARATOR = "_";

    private FileSystem fileSystem;
    private String workingRoot;

    public FileSystemCache() {
        this(DEFAULT_ROOT);
    }

    /**
     * @param workingRoot 在哪个目录下工作。
     */
    public FileSystemCache(String workingRoot) {
        this.workingRoot = workingRoot;
    }

    public FileSystem getFileSystem() {
        return fileSystem;
    }

    @Autowired
    public void setFileSystem(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    public String getWorkingRoot() {
        return workingRoot;
    }

    public void setWorkingRoot(String workingRoot) {
        this.workingRoot = workingRoot;
    }

    @Override
    public void put(String key, Object value, int ttl) {
        try {
            getFileSystem().put(getFilePath(key, ttl), serialize(value));
        }
        catch (InvalidFileException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            getFileSystem().put(getFilePath(key, -1), serialize(value));
        }
        catch (InvalidFileException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object get(String key) {
        try {
            Optional<PathMeta> opt = getFileSystem().files(getWorkingRoot(), getKeyPrefix(key)).stream().findFirst();
            if (opt.isPresent()) {
                PathMeta meta = opt.get();
                String filename = meta.getName();
                long now = System.currentTimeMillis();
                long creationTime = meta.getCreationTime().toEpochMilli();
                int ttl = getTTLFromFileName(filename);

                if (creationTime + ttl <= now) {
                    return null;
                }

                return deserialize(getFileSystem().read(meta.getPath()));
            }
        }
        catch (PathNotFoundException e) {
            return null;
        }
        catch (InvalidFileException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    @Override
    public void touch(String key, Integer ttl) {
        try {
            Optional<PathMeta> opt = getFileSystem().files(getWorkingRoot(), getKeyPrefix(key)).stream().findFirst();
            if (opt.isPresent()) {
                PathMeta meta = opt.get();

                getFileSystem().touchCreation(meta.getPath());
                if (null != ttl) {
                    getFileSystem().move(meta.getPath(), getFilePath(key, ttl));
                }
            }

            throw new InvalidFileException("找不到cache key：" + key);
        }
        catch (FileSystemException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, Object> getAlive() {
        long now = System.currentTimeMillis();
        Stream<PathMeta> stream = getMetaStream();

        return stream.filter(meta -> {
            String filename = meta.getName();
            long creationTime = meta.getCreationTime().toEpochMilli();
            int ttl = getTTLFromFileName(filename);

            return creationTime + ttl > now;
        }).collect(collector);
    }

    @Override
    public Map<String, Object> getExpired() {
        long now = System.currentTimeMillis();
        Stream<PathMeta> stream = getMetaStream();

        return stream.filter(meta -> {
            String filename = meta.getName();
            long creationTime = meta.getCreationTime().toEpochMilli();
            int ttl = getTTLFromFileName(filename);

            return creationTime + ttl <= now;
        }).collect(collector);
    }

    @Override
    public void remove(String... keys) {
        Stream.of(keys).forEach(key -> {
            Optional<PathMeta> opt = null;
            try {
                opt = getFileSystem().files(getWorkingRoot(), getKeyPrefix(key)).stream().findFirst();
            }
            catch (PathNotFoundException e) {
                return;
            }

            if (opt.isPresent()) {
                getFileSystem().delete(opt.get().getPath());
            }
        });
    }

    @Override
    public Map<String, Object> find(String search) {
        try {
            return getFileSystem().files(getWorkingRoot()).stream()
                .filter(meta -> meta.getName().contains(search))
                .collect(collector);
        }
        catch (PathNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private String getKeyPrefix(String key) {
        return key + NAME_SEPARATOR;
    }

    private String getFileName(String key, int ttl) {
        String filename = key + NAME_SEPARATOR + ttl;
        FileSystemHelper.assertFileNameValid(filename);
        return filename;

    }

    private String getFilePath(String key, int ttl) {
        return getWorkingRoot() + FileSystem.PATH_SEPARATOR + getFileName(key, ttl);
    }

    private int getTTLFromFileName(String name) {
        return Integer.parseInt(name.substring(name.indexOf(NAME_SEPARATOR) + NAME_SEPARATOR.length()));
    }

    private String getKeyFromFileName(String name) {
        return name.substring(0, name.indexOf(NAME_SEPARATOR));
    }

    private Stream<PathMeta> getMetaStream() {
        Stream<PathMeta> stream;

        try {
            stream = getFileSystem().files(getWorkingRoot()).stream();
        }
        catch (PathNotFoundException e) {
            throw new RuntimeException(e);
        }

        return stream;
    }

    private InputStream serialize(Object data) {
        return new ByteArrayInputStream(SerializationUtils.serialize(data));
    }

    private Object deserialize(InputStream stream) {
        try {
            return SerializationUtils.deserialize(StreamUtils.copyToByteArray(stream));
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Collector<PathMeta, ?, Map<String, Object>> collector =
        Collectors.toMap(
            meta -> getKeyFromFileName(meta.getName()),
            meta -> {
                try {
                    return deserialize(getFileSystem().read(meta.getPath()));
                }
                catch (InvalidFileException e) {
                    throw new RuntimeException(e);
                }
            }
        );
}