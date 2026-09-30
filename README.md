# Ryzix-OreESP-Fabric

Minimal client-side OreESP for **Minecraft 1.20.4** (Fabric Loader 0.15.11, Fabric API 0.97.3+1.20.4, Java 17).

- `Z` toggles OreESP (off by default; rebind in Controls > Ryzix-OreESP). FullBright is always on automatically. No menu.
- Highlights iron, gold, lapis, diamond ore (incl. deepslate variants) below Y=64.
- Scans 4 chunks (your chunk + 3 nearest neighbours), every 2s (and instantly when you change chunk); render only draws the cached result.

## Build
`./gradlew build` -> `build/libs/ryzix-oreesp-*.jar`. GitHub Actions builds and releases on every push to `main`.

Use only where client-side ESP is allowed (singleplayer / permissive servers).

## License
CC0-1.0
