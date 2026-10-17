# RESULT — Sable × ScalableLux compat fix

## Problem

`ArrayIndexOutOfBoundsException` (index -7682932, length 25) in ScalableLux's `StarLightEngine.getEmptinessMap` when creating Sable objects (plots / sub-levels).

Root cause: Sable places plots ~1.28M chunks out (`SubLevelContainer.DEFAULT_ORIGIN`) and its chunk-routing mixins can make ScalableLux's 5×5 light-window cache return a chunk whose `ChunkPos` doesn't match the requested coordinates (e.g. the `(0,0)` empty-chunk placeholder). `handleEmptySectionChanges` then re-derives chunkX/Z from `chunk.getPos()` and indexes the 25-slot `emptinessMapCache` window out of bounds (crash index -7682932 = `(0,0)` chunk read through a window centered at `(1280004, 1280588)`).

## Changes

### 1. `src/main/java/com/example/examplemod/mixin/StarLightEngineMixin.java` (new)

Injects at `getChunkInCache(II)` RETURN on `ca.spottedleaf.starlight.common.light.StarLightEngine`:

- If returned chunk's `ChunkPos` (x or z) doesn't match the requested `(chunkX, chunkZ)`, sets return value to `null`.
- ScalableLux already treats a null cache result exactly like an unloaded chunk — lighting for that chunk is safely skipped instead of crashing.
- String-targeted `@Mixin(targets = ..., remap = false)` + `require = 0` so the compat mod builds and loads even without ScalableLux installed.

```java
@Mixin(targets = "ca.spottedleaf.starlight.common.light.StarLightEngine", remap = false)
public abstract class StarLightEngineMixin {
    @Inject(method = "getChunkInCache", at = @At("RETURN"), cancellable = true, require = 0)
    private void sablescalableluxcompat$verifyChunkPos(final int chunkX, final int chunkZ, final CallbackInfoReturnable<ChunkAccess> cir) {
        final ChunkAccess chunk = cir.getReturnValue();
        if (chunk != null && (chunk.getPos().x != chunkX || chunk.getPos().z != chunkZ)) {
            cir.setReturnValue(null);
        }
    }
}
```

### 2. `src/main/resources/sablescalableluxcompat.mixins.json` (new)

```json
{
  "required": false,
  "minVersion": "0.8",
  "package": "com.example.examplemod.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["StarLightEngineMixin"],
  "injectors": { "defaultRequire": 0 }
}
```

### 3. `src/main/templates/META-INF/neoforge.mods.toml`

- Registered mixin config:

```toml
[[mixins]]
config="${mod_id}.mixins.json"
```

- Added optional load-order dependencies so the mod loads after (but does not require) both target mods:

```toml
[[dependencies.${mod_id}]]
modId="sable"
type="optional"
versionRange="*"
ordering="AFTER"
side="BOTH"

[[dependencies.${mod_id}]]
modId="scalablelux"
type="optional"
versionRange="*"
ordering="AFTER"
side="BOTH"
```

### 4. ScalableLux submodule restore

Restored missing `ScalableLux/ScalableLux/FlowSched/` submodule content (copied from sibling checkout) so ScalableLux builds.

### 5. `HotSwap.java` (project root)

Standalone verification helper used during development to load the mixin jar and transform `StarLightEngine` outside the game — confirms the mixin config binds and the target class transforms. Not part of the mod jar.

## Verification

- `getChunkInCache(II)` signature confirmed in compiled ScalableLux jar (returns `ChunkAccess`) — mixin binds by name.
- Compat mod: `sh gradlew build` → BUILD SUCCESSFUL; jar contains mixin class + `sablescalableluxcompat.mixins.json` + mods.toml `[[mixins]]` registration.
- ScalableLux: `./gradlew build` → BUILD SUCCESSFUL.

## Notes

- Live in-game reproduction not run (needs full MC client environment). Recommend smoke test: create a Sable plot with ScalableLux installed.
