package com.netsight.gateway.sync;

import com.netsight.gateway.core.ConfigHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Stream;

@Slf4j
@Component
public class LocalInventoryStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int KEEP_BACKUPS = 20;

    private final Path path;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public LocalInventoryStore(ConfigHolder configHolder) {
        this(resolve(configHolder), new ObjectMapper());
    }

    public LocalInventoryStore(Path path) {
        this(path, new ObjectMapper());
    }

    LocalInventoryStore(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    public LocalInventoryDocument read() {
        synchronized (lock) {
            try {
                if (!Files.exists(path)) {
                    LocalInventoryDocument doc = newDocument();
                    write(doc);
                    return doc;
                }
                String json = Files.readString(path);
                JsonNode root = mapper.readTree(json);
                if (!root.path("devices").isArray()) {
                    throw new IllegalStateException("mapping.json 的 devices 字段不是数组: " + path);
                }
                LocalInventoryDocument doc = mapper.convertValue(root, LocalInventoryDocument.class);
                if (doc.getRevision() <= 0) {
                    doc.setRevision(1);
                }
                if (doc.getDevices() == null) {
                    doc.setDevices(new java.util.ArrayList<>());
                }
                return doc;
            } catch (IOException e) {
                throw new IllegalStateException("读取 mapping.json 失败: " + path, e);
            }
        }
    }

    public <T> T mutate(Function<LocalInventoryDocument, T> operator) {
        synchronized (lock) {
            LocalInventoryDocument doc = read();
            T result = operator.apply(doc);
            doc.setRevision(doc.getRevision() + 1);
            doc.setUpdatedAt(LocalDateTime.now().format(TS));
            write(doc);
            return result;
        }
    }

    public void bumpRevisionTo(long minRevision) {
        synchronized (lock) {
            LocalInventoryDocument doc = read();
            if (doc.getRevision() < minRevision) {
                doc.setRevision(minRevision);
                doc.setUpdatedAt(LocalDateTime.now().format(TS));
                write(doc);
            }
        }
    }

    public long getRevision() {
        return read().getRevision();
    }

    public int getDeviceCount() {
        return read().getDevices().size();
    }

    public Path getPath() {
        return path;
    }

    private LocalInventoryDocument newDocument() {
        LocalInventoryDocument doc = new LocalInventoryDocument();
        doc.setVersion("local-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        doc.setRevision(1);
        doc.setUpdatedAt(LocalDateTime.now().format(TS));
        return doc;
    }

    private void write(LocalInventoryDocument doc) {
        try {
            Files.createDirectories(path.getParent());
            backupIfExists();
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入 mapping.json 失败: " + path, e);
        }
    }

    private void backupIfExists() throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Path backup = path.resolveSibling(path.getFileName() + ".bak-" + LocalDateTime.now().format(DAY));
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        try (Stream<Path> stream = Files.list(path.getParent())) {
            stream.filter(p -> p.getFileName().toString().startsWith(path.getFileName() + ".bak-"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .skip(KEEP_BACKUPS)
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("清理清单备份失败: {}", p, e);
                        }
                    });
        }
    }

    private static Path resolve(ConfigHolder configHolder) {
        String mp = configHolder.getConfig() == null ? null : configHolder.getConfig().getMappingPath();
        if (mp != null && !mp.isBlank()) {
            return Paths.get(mp);
        }
        return Paths.get(configHolder.getConfDir(), "mapping.json");
    }
}
