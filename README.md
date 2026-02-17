# Liar Bar (Paper Plugin Monorepo)

This repository contains a modular Minecraft Paper plugin implementation for the Liar Bar gameplay.

## Modules

- `core`: Pure Java gameplay rules and runtime state machine.
- `paper-adapter`: Application and translation layer between core events and plugin-facing interactions.
- `paper-plugin`: Runnable Paper plugin bootstrap, command layer, integrations (Vault, PacketEvents), and stats persistence.
- `_source_datapack`: Original datapack source references.
- `docs`: Project notes and migration documents.

## Build

Requirements:

- JDK `25`
- Maven `3.9+`

Command:

```bash
mvn clean verify
```

## Output Artifact

After a successful build, the Paper plugin jar is generated at:

`paper-plugin/target/liar-bar-paper-plugin-1.0.0-SNAPSHOT.jar`

