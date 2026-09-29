# Ryzix-OreESP-Fabric

Minimal client-side OreESP for **Minecraft 1.20.4** (Fabric Loader 0.15.11, Fabric API 0.97.3+1.20.4, Java 17).

- `Z` toggles OreESP (rebind in Controls > Ryzix-OreESP). No menu, no other modules.
- Highlights iron, gold, lapis, diamond ore (incl. deepslate variants) below Y=64.
- Scans only your current chunk, every 3s (and instantly when you enter a new chunk); render only draws the cached result.

## Build
`./gradlew build` -> `build/libs/ryzix-oreesp-*.jar`. GitHub Actions builds and releases on every push to `main`.

Use only where client-side ESP is allowed (singleplayer / permissive servers).

## License
CC0-1.0
