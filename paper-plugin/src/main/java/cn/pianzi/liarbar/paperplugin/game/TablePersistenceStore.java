package cn.pianzi.liarbar.paperplugin.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * JSON fallback store for table locations.
 * Used as a secondary persistence layer when database table restore is empty or unavailable.
 */
public final class TablePersistenceStore {
    private static final TypeReference<List<SavedTable>> TABLE_LIST_TYPE = new TypeReference<>() {
    };

    private final Path filePath;
    private final ObjectMapper mapper;

    public TablePersistenceStore(Path dataFolder) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        this.filePath = dataFolder.resolve("tables.json");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(List<SavedTable> tables) throws Exception {
        List<SavedTable> safe = tables == null ? List.of() : List.copyOf(tables);
        Files.createDirectories(filePath.getParent());
        mapper.writeValue(filePath.toFile(), safe);
    }

    public List<SavedTable> load() throws Exception {
        if (!Files.exists(filePath)) {
            return List.of();
        }
        List<SavedTable> tables = mapper.readValue(filePath.toFile(), TABLE_LIST_TYPE);
        return tables == null ? List.of() : List.copyOf(tables);
    }
}
