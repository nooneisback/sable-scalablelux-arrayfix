# FINDINGS — Sable × ScalableLux crash analysis

Working notes from source analysis of `Sable/` and `ScalableLux/` for the
`ArrayIndexOutOfBoundsException` thrown when creating new Sable objects.
Intended as input for a later diagnosis/fix session. Everything below was
verified against the sources in this repo unless explicitly marked **HYPOTHESIS**.

## 1. Issue recap

- NeoForge 1.21.1 compat mod project (template `sablescalableluxcompat`).
- Crash (`PROJECT.md`):

```
java.lang.ArrayIndexOutOfBoundsException: Index -7682932 out of bounds for length 25
  at ...scalablelux...StarLightEngine.getEmptinessMap(StarLightEngine.java:384)
  at ...StarLightEngine.handleEmptySectionChanges(StarLightEngine.java:747)
  at ...StarLightEngine.blocksChangedInChunk(StarLightEngine.java:424)
  at ...StarLightInterface.handleUpdateInternal(StarLightInterface.java:659)   [mixin APP: sablescalablelux.mixins.json:StarLightInterfaceMixin]
  at ...StarLightInterface.propagateChanges(StarLightInterface.java:579)       [mixin APP: sablescalablelux.mixins.json:StarLightInterfaceMixin]
  at ...vanillainterface.CommonLightEngineUtils.runLightUpdates(CommonLightEngineUtils.java:28)
  at ...vanillainterface.BaseLevelLightEngineVanillaInterface.runLightUpdates(BaseLevelLightEngineVanillaInterface.java:57)
```

- Happens only **sometimes**, when creating new Sable objects (plots/sub-levels).

## 2. Critical project-layout facts

1. **This repo contains NO compat-mod code.** `src/` is an untouched NeoForge MDK
   template (`com/example/examplemod/...`: `Config.java`, `sablescalableluxcompat.java`,
   `sablescalableluxcompatClient.java`; no mixins, no `sablescalablelux.mixins.json`).
   The fix session must write the compat layer from scratch.
2. The **crashing compat mod** in the log had mod id `sablescalablelux`
   (config `sablescalablelux.mixins.json`, mixin `StarLightInterfaceMixin` targeting
   ScalableLux's `StarLightInterface`, attached to the `propagateChanges` /
   `handleUpdateInternal` frames). Its source is not in this repo.
3. **Sable explicitly declares ScalableLux incompatible**:
   - `Sable/fabric/src/main/resources/fabric.mod.json` line 50: `"breaks": { "scalablelux": "*" }`
   - `Sable/neoforge/src/main/resources/META-INF/neoforge.mods.toml` lines 67-70:
     `[[dependencies.sable]] modId = "scalablelux" type = "incompatible"`
   A compat mod may have to work around these gates (dependency override etc.).
4. **Version drift**: the crashing jar (ScalableLux `0.3.0-alpha.0.8+1.21.1`) has a
   `ca.spottedleaf.starlight.common.light.vanillainterface` package
   (`CommonLightEngineUtils`, `BaseLevelLightEngineVanillaInterface`) that does **not
   exist** in this repo's ScalableLux source. In the repo copy, `LevelLightEngine.runLightUpdates`
   is overwritten in `LevelLightEngineMixin` to call `this.lightEngine.propagateChanges()`
   directly. Line numbers below are from the repo copy and may differ slightly from the jar.

## 3. Crash mechanics (what "length 25" and the index mean)

- `StarLightEngine` (`ScalableLux/ScalableLux/src/main/java/ca/spottedleaf/starlight/common/light/StarLightEngine.java`)
  keeps **5×5 chunk-window caches**. `emptinessMapCache` is a `boolean[25][]`
  (5×5 chunk area — this matches `length 25` in the exception).
- Cache slot lookup (repo lines 381-387):

```java
protected final boolean[] getEmptinessMap(final int chunkX, final int chunkZ) {
    return this.emptinessMapCache[chunkX + 5*chunkZ + this.chunkIndexOffset];   // line ~382/384
}
protected final void setEmptinessMapCache(final int chunkX, final int chunkZ, final boolean[] emptinessMap) {
    this.emptinessMapCache[chunkX + 5*chunkZ + this.chunkIndexOffset] = emptinessMap;
}
```

- `chunkIndexOffset` is set by `setupEncodeOffset` (lines 151-169) so that the window
  `[center-2, center+2]` maps to slots `[0, 24]`. `setupCaches` (lines 172-208) is called
  with the *center chunk* and fills the window from
  `chunkProvider.getChunkForLighting(cx, cz)`.
- Crash index `-7682932` ⇒ `chunkX + 5*chunkZ` was ~1.5M chunks away from the window
  center (e.g. Δz ≈ -1,536,588 chunks ≈ -24.6M blocks). This is *far* outside the ±2 chunk
  window ⇒ **some call used a chunk coordinate from a completely different coordinate
  space than the one the caches were centered on** (or offsets were corrupted).
- Call path (repo copy):
  - `StarLightInterface.handleUpdateInternal` (lines 626-655): takes `task.chunkCoordinate`
    → `chunkX/chunkZ` via `CoordinateUtils.getChunkX/Z` → calls
    `skyEngine/blockEngine.blocksChangedInChunk(lightAccess, chunkX, chunkZ, positions, sectionChanges)`.
  - `StarLightEngine.blocksChangedInChunk` (lines 413-433): calls
    `setupCaches(lightAccess, chunkX*16+7, 128, chunkZ*16+7, true, true)` (note: hardcoded
    centerY `128`, `relaxed=true`), then `getChunkInCache(chunkX, chunkZ)`; if a chunk is
    found and `changedSections != null` → `handleEmptySectionChanges(...)`.
  - `handleEmptySectionChanges(lightAccess, chunk, emptinessChanges, unlit)` (starts ~line 737):
    **re-derives `chunkX = chunk.getPos().x; chunkZ = chunk.getPos().z;`** (lines 690-691
    show this pattern in `forceHandleEmptySectionChanges`; the private overload does the same).
    It then allocates the per-chunk emptiness map if missing
    (`needsInit` branch, repo line ~747: `setEmptinessMapCache(chunkX, chunkZ, new boolean[WorldUtil.getTotalSections(world)])`),
    walks `emptinessChanges` (sized `maxSection - minSection + 1` = 24 for a default 1.21.1 world),
    inits neighbor nibbles (`initNibble(dx+chunkX, dy+sectionY, dz+chunkZ)`), and probes
    neighbor emptiness via `getEmptinessMap(dx+chunkX, dz+dz2+chunkZ)` (~line 799, offsets ≤ 2).

**Implication:** if the `ChunkAccess` stored in the cache slot for a requested global
coordinate carries a `ChunkPos` from a different space (e.g. plot-*local* coords, or vice
versa), `handleEmptySectionChanges` immediately computes wildly out-of-window indices ⇒
exactly this crash. All engine-internal offsets are ±2, so a *consistent* coordinate
space can never produce index -7682932.

## 4. ScalableLux architecture (verified, repo copy)

Files under `ScalableLux/ScalableLux/src/main/java/ca/spottedleaf/starlight/`:

- `common/light/StarLightEngine.java` (1574 lines) — propagation engine; per-instance
  mutable window caches + encode offsets (`setupEncodeOffset`, `setupCaches`,
  `destroyCaches`); `blocksChangedInChunk` (413), `forceHandleEmptySectionChanges` (~686,
  uses `chunk.getPos()` at 690-691), public `handleEmptySectionChanges(x,z)` (710-725),
  private `handleEmptySectionChanges(chunk,…)` (~737+, allocation at ~745-748, neighbor
  probe at ~799). Constructor (136-149) sizes `sectionCache`/`nibbleCache`/`notifyUpdateCache`
  as `5*5*((maxLightSection-minLightSection+1)+2)`.
- `common/light/StarLightInterface.java` (764 lines) — per-`LevelLightEngine` bridge:
  - Constructor (69-89): resolves `world` from `lightAccess.getLevel()`, computes
    `minSection/maxSection/minLightSection/maxLightSection` via `WorldUtil`, detects
    client side, dummy readers.
  - `blockChange` (398-404) bounds-checks Y against `WorldUtil.getMinBlockY/MaxBlockY`.
  - `sectionChange` (406-412) → `lightQueue.queueSectionChange`.
  - `propagateChanges` (543-565): non-threaded path obtains **pooled** engines
    (`getSkyLightEngine`/`getBlockLightEngine`, pools `cachedSkyPropagators`/`cachedBlockPropagators`,
    350-396) and drains `lightQueue.removeFirstTask()` → `handleUpdateInternal`.
  - Threaded path `schedulePropagation0` (572-617) when
    `GlobalExecutors.ENABLED && lightEngine instanceof ThreadedLevelLightEngine`:
    schedules per-chunk tasks via `SchedulingUtil.scheduleTask(instanceId, …, chunkX, chunkZ, 2)`,
    tracks per-chunk `CompletableFuture`s in `chunkFutures`.
  - `handleUpdateInternal` (626-655): runs `task.lightTasks` runnables **first**, then
    `blocksChangedInChunk` for sky+block engines, then `checkChunkEdges`, then completes
    `task.onComplete`.
  - `LightQueue` (657-760): `chunkTasks` map keyed by `CoordinateUtils.getChunkKey`
    (`(z<<32) | (x & 0xFFFFFFFF)`); `queueSectionChange` sizes `changedSectionSet` as
    `Boolean[maxSection - minSection + 1]` and stores at index `pos.getY() - minSection`;
    `ChunkTasks` has `changedPositions`, `changedSectionSet`, `queuedEdgeChecksSky/Block`,
    `lightTasks`, `isTicketAdded`, `onComplete`, `chunkCoordinate`.
  - `CHUNK_WORK_TICKET` `TicketType` field exists (line 42) — server-side chunk-load
    ticketing tied to `scheduleChunkLight`/`removeChunkTasks` (535-541).
- `mixin/common/lightengine/LevelLightEngineMixin.java` (284 lines) — targets vanilla
  `LevelLightEngine`, implements `StarLightLightingProvider`:
  - Constructor inject: creates one `StarLightInterface` per engine; uses
    `new StarLightInterface(chunkProvider, …)` when the chunk provider exposes a `Level`,
    else `new StarLightInterface(null, …)` ("empty world"; comment: avoid
    `ClassCastException` for `LightChunkGetter`s not having a level).
  - `checkBlock` → `lightEngine.blockChange(pos.immutable())`.
  - `hasLightWork` → `hasUpdates`; `runLightUpdates` → `propagateChanges()` (repo copy).
  - `updateSectionStatus` → `lightEngine.sectionChange` (line 109-112).
  - `setLightEnabled` (121-134): tracks chunk keys in `@Unique LongOpenHashSet
    scalablelux$lightingEnabledChunks` ("needed for proper culling chunks in client").
  - `propagateLightSources` → no-op; `retainData` → no-op; `queueSectionData` → no-op-ish.
  - `getLayerListener` → `lightEngine.getBlockReader()/getSkyReader()`.
  - `lightOnInSection` (209-213) consults `scalablelux$lightingEnabledChunks` plus
    `blockLightMap`/`skyLightMap` (`@Unique` maps).
- `common/util/CoordinateUtils.java` (129 lines) — vanilla-compatible chunk keys;
  `getChunkKey(BlockPos)` line 19-21; `getChunkX/getChunkZ` decode via 32-bit shift
  (mangled lines 39-44).
- Also present: `common/light/SkyStarLightEngine.java`, `BlockStarLightEngine.java`,
  `SWMRNibbleArray.java`, `StarLightLightingProvider.java`, `common/thread/GlobalExecutors`,
  `SchedulingUtil`, `common/world/ExtendedWorld`, `common/chunk/ExtendedChunk`
  (per-chunk nibble/emptiness accessors used by the engine).

## 5. Sable architecture (verified, repo copy)

Files under `Sable/common/src/main/java/dev/ryanhcode/sable/`:

- **Sub-levels/plots are embedded in a parent `ServerLevel`.** `SubLevelContainer`
  (`api/sublevel/SubLevelContainer.java`) manages plots and has an **`origin`**
  (used e.g. in `ServerLevelPlot.save()`: `this.plotPos.x - this.container.getOrigin().x`).
- `sublevel/plot/LevelPlot.java` (446 lines):
  - Constructor (108-114): `plotPos = new ChunkPos(x, z)` where x/z are *"the global X/Z
    coordinate of the plot, in units of `1 << logSize` chunks"*; `chunks` array sized
    `(1<<logSize)^2`.
  - `getLightEngine()` (162, abstract): *"Returns lighting engine this sub-level should
    use… ServerSubLevels having their own lighting engine"*.
  - `newChunk` (146-155): converts global→local (`toLocal`), creates
    `PlotChunkHolder.create(chunk.getLevel(), pos, this.getLightEngine(), chunk)` — holder
    carries the plot light engine.
  - `newEmptyChunk` (167-184): builds a fresh `LevelChunk` directly via `new LevelChunk(level, pos, …)`.
- `sublevel/plot/ServerLevelPlot.java` (688 lines):
  - Constructor (90-97): **each plot gets its OWN vanilla `LevelLightEngine`**:
    `this.lightEngine = new LevelLightEngine(chunkSource, parentLightEngine.blockEngine != null, parentLightEngine.skyEngine != null)`
    with the **parent level's `ChunkSource`**. (With ScalableLux installed, ScalableLux's
    constructor mixin turns this into a StarLight-backed engine.)
  - `tick()` (130-137): `do { this.lightEngine.runLightUpdates(); } while (hasLightWork())`.
  - `initializeLight` (181-196): for each non-air section calls
    `lightEngine.updateSectionStatus(SectionPos.of(pos, sectionY), false)`; then
    `lightEngine.setLightEnabled(pos, chunk.isLightCorrect())`, `retainData(pos, false)`.
  - `correctLight` (198-205): `propagateLightSources` + `setLightCorrect(true)`.
  - `lightChunk` (207-211): `chunk.initializeLightSources()` + `initializeLight` + `correctLight`.
  - `addChunkHolder` (220-258): puts the holder into the **real server chunk map at the
    global chunk pos** (`cache.chunkMap.updatingChunkMap.put(globalChunkPos.toLong(), holder)`),
    `chunk.setLightCorrect(false)`, optional `lightChunk(chunk)`, then drains:
    `do { this.lightEngine.runLightUpdates(); } while (…)`; then sends chunks to tracking players
    (`SubLevelPlayerChunkSender.sendChunk(player.connection::send, this.lightEngine, …)`).
  - `newNonLitChunk` (318-330): fresh empty `LevelChunk`, `newChunk(pos, chunk, false)`.
  - Unload path (~143-175): `retainData(pos, false)`, `setLightEnabled(pos, false)`,
    `queueSectionData(BLOCK/SKY, …)`, `updateSectionStatus(…, true)` loop, removes from
    real chunk map (`updatingChunkMap.remove`), `serverLevel.unload(levelChunk)`.
  - `save()` (335+): reads light via `this.lightEngine.getLayerListener(LightLayer.BLOCK/SKY).getDataLayerData(sectionPos)`
    (lines 365-373).
- `mixin/plot/lighting/BlockAndTintGetterMixin.java` (68 lines): overwrites
  `BlockAndTintGetter.getBrightness(LightLayer, BlockPos)`: if the receiver is a
  `SubLevelContainerHolder` and its level is a `ServerLevel`, looks up the plot by
  `new ChunkPos(blockPos)` and routes the query to `plot.getLightEngine()`
  (`engine.getLayerListener(lightLayer).getLightValue(blockPos)`); similar
  `getRawBrightness` routing below.
- **Not yet opened but flagged relevant** (grep hits around light handling):
  - `mixin/plot/ServerChunkCacheMixin.java` (~lines 97-101) — likely intercepts chunk
    lookups (possibly `getChunkForLighting`/`getChunk`) for plot-global positions.
  - `mixin/plot/ClientChunkCacheMixin.java` (~127-131) — client-side counterpart.
  - `mixin/plot/lighting/LevelChunkMixin.java`, `ClientPacketListenerMixin.java`,
    `RenderChunkRegionMixin.java`.
  - `sublevel/plot/PlotChunkHolder.java` — holds chunk + plot light engine.
  - `sublevel/plot/EmbeddedPlotLevelAccessor.java`, `sublevel/plot/LevelPlot.toGlobal/toLocal`.

## 6. Reproduction flow (creating a Sable object)

`newEmptyChunk`/`newNonLitChunk` → `newChunk` → `addChunkHolder(initializeLighting=true)`
→ `lightChunk` → `initializeLight` queues `sectionChange(false)` per non-air section +
`setLightEnabled` → `addChunkHolder` drains `runLightUpdates()` →
`StarLightInterface.propagateChanges` → `handleUpdateInternal` → `blocksChangedInChunk`
→ `handleEmptySectionChanges` → **crash** in `getEmptinessMap`.

## 7. Root-cause hypotheses (ranked, HYPOTHESIS)

- **H1 — coordinate-space mismatch (global vs plot-local) — most likely.**
  Sable places plot chunks at far-away *global* coordinates (origin-based; plot coords
  are in units of `1<<logSize` chunks). The crash index implies a ~1.5M-chunk Δz, i.e.
  ~24.6M blocks — plausibly Sable's far-out plot placement region (within the ±30M-block
  world border). If any chunk returned by the (Sable-mixin-modified) chunk source carries a
  `ChunkPos` in a different space than the light-queue task coordinate (e.g. holder chunk
  with local pos, or a remapped chunk), then `handleEmptySectionChanges` re-derives
  `chunkX/Z` from `chunk.getPos()` and indexes the 5×5 window with out-of-space
  coordinates ⇒ AIOOBE with a huge index. "Sometimes" = only when the section-change /
  emptiness-init path runs (non-air sections present, or first-light init) and only for
  certain plot positions.
- **H2 — pooled-engine race.** ScalableLux pools `SkyStarLightEngine`/`BlockStarLightEngine`
  per `StarLightInterface` and has a threaded mode (`GlobalExecutors.ENABLED`,
  `schedulePropagation0`). Sable calls `runLightUpdates()` synchronously and repeatedly on
  plot engines during chunk add/tick. Concurrent use of a shared engine instance would
  corrupt the mutable `chunkIndexOffset`/caches mid-window ⇒ garbage index. The previous
  compat `StarLightInterfaceMixin` (applied to `propagateChanges`/`handleUpdateInternal`)
  may have introduced or worsened this.
- **H3 — lifecycle/queue mismatch.** Sable bypasses the normal chunk pipeline (direct
  `new LevelChunk`, manual `setLightCorrect`, manual chunk-map insertion/removal). Stale or
  foreign `ChunkTasks` (keys from another engine's space) could in principle be drained by
  the wrong engine. Less consistent with the specific `getEmptinessMap` site than H1/H2.
- **H4 — the old compat mod itself was buggy.** Its mixin sits exactly on the crashing
  frames; without its source we cannot rule out that the redirect itself passed a wrong
  engine/lightAccess/coordinate. Its mod id (`sablescalablelux`) differs from this template's
  (`sablescalableluxcompat`), so the fix session is effectively rewriting it.

## 8. Recommended verification steps for the next session

1. Open `Sable/.../mixin/plot/ServerChunkCacheMixin.java` + `ClientChunkCacheMixin.java` +
   `LevelChunkMixin.java` and `SubLevelContainer.java` (origin, plot coordinate mapping,
   `getPlot`), plus `PlotChunkHolder`. Determine exactly which `ChunkPos` space plot chunks
   carry inside the real chunk map and what `getChunkForLighting` returns for plot coords.
2. Establish Sable's plot placement arithmetic (`toGlobal`/`toLocal`, origin selection) and
   check whether Δ(global, local) matches ~1.5M chunks (would confirm H1).
3. Check ScalableLux `GlobalExecutors` config defaults (threaded server lighting?) to
   assess H2.
4. Decide the compat strategy the new `sablescalableluxcompat` mod needs, e.g.:
   - a `StarLightInterface`/`StarLightEngine` mixin that clamps/validates window indices or
     re-keys Sable plot coordinates into a consistent space;
   - making plot light engines' queues/engines isolated (no cross-engine drain);
   - and handling Sable's `breaks`/`incompatible` declaration.

## 9. Key file index (with line numbers, repo copy)

| File | Lines | What |
|---|---|---|
| `ScalableLux/.../common/light/StarLightEngine.java` | 136-149 | ctor, cache array sizes |
| | 151-169 | `setupEncodeOffset` (index offsets) |
| | 172-208 | `setupCaches` (5×5 window fill via `getChunkForLighting`) |
| | 381-387 | **`getEmptinessMap`/`setEmptinessMapCache` — crash site** |
| | 413-433 | `blocksChangedInChunk` |
| | ~690-691 | `chunk.getPos()` re-derivation of chunkX/Z |
| | 710-725 | public `handleEmptySectionChanges(x,z)` |
| | ~737-805 | private `handleEmptySectionChanges(chunk,…)` incl. `needsInit` (~747), neighbor probe (~799) |
| `ScalableLux/.../common/light/StarLightInterface.java` | 69-89 | ctor (sections via `WorldUtil`) |
| | 350-396 | pooled engines |
| | 398-412 | `blockChange` / `sectionChange` |
| | 543-617 | `propagateChanges` / threaded `schedulePropagation0` |
| | 626-655 | `handleUpdateInternal` |
| | 657-760 | `LightQueue` / `ChunkTasks` |
| `ScalableLux/.../mixin/common/lightengine/LevelLightEngineMixin.java` | 109-134 | `updateSectionStatus`, `setLightEnabled` (`scalablelux$lightingEnabledChunks`) |
| `ScalableLux/.../common/util/CoordinateUtils.java` | 19-37 | chunk key encode/decode |
| `Sable/.../sublevel/plot/LevelPlot.java` | 108-114, 146-184 | plot ctor (global plot coords), `newChunk`, `newEmptyChunk` |
| `Sable/.../sublevel/plot/ServerLevelPlot.java` | 90-97 | per-plot `LevelLightEngine` on parent `ChunkSource` |
| | 130-137, 250-252 | `tick()` / `runLightUpdates` drain loops |
| | 181-211 | `initializeLight`/`correctLight`/`lightChunk` |
| | 220-258 | `addChunkHolder` (real chunk map insert at global pos) |
| `Sable/.../mixin/plot/lighting/BlockAndTintGetterMixin.java` | 21-41 | brightness routing to plot engine |
| `Sable/fabric/src/main/resources/fabric.mod.json` | 48-52 | `breaks: scalablelux` |
| `Sable/neoforge/src/main/resources/META-INF/neoforge.mods.toml` | 67-70 | `incompatible` dep |
