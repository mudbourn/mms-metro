# Chunk-independent trains

A train is data, not entities. Its state lives in per-world saved data and
advances every server tick whether or not its chunks are loaded. Car entities
are views. Each train holds one small ticket on each chunk its cars occupy,
so its views stay loaded.

## Today

- `ConsistManager.updateForcedChunks` adds a `TRAIN` ticket of radius
  `chunkRadius + 2` around every chunk a car sits in, and swaps tickets on
  every chunk crossing. That ticket work dominates the spark profile.
- A `Consist` exists only while its cars are loaded; `reconcile()` rebuilds it
  from loaded cars, so trains must hold chunks to keep existing.
- Per-tick work reads the world: `refreshMarkers()` rescans every 20 ticks,
  `blockingTrainHoldArc()` scans car entities, `reverse()` walks a new
  `RailPath` from the lead's block, junction priority and speakers scan for
  block entities.

## State

`MetroState extends PersistentState`, one per `ServerWorld`, holds:

- `paths`: baked paths keyed by `(origin, initialDir)`, shared by every train
  on that path.
- `trains`: per consist id, the path key, `headArc`, `speed`, phase, dwell
  timer, `nextStationIndex`, `firedThroughArc`, ease-down fields, buffer hold,
  depart timer, `ringCommitted`, car count, and HUD identity (line, direction,
  colour).
- `pendingWaves`: circulate waves, triggered by a consist id and arc instead of
  a lead entity reference.

`Consist` keeps its motion logic but reads and writes this record instead of
fields mirrored onto cars. `ConsistManager.tickWorld` ticks every train in the
state on `END_WORLD_TICK`, on the main thread. `END_WORLD_TICK` fires for every
world each server tick, even with no players, so schedules keep time.

## Baking

A `BakedPath` is everything `RailPath` holds today plus what ticking currently
looks up in the world:

- points, nodes, cumulative arc, loop flag and loop start arc;
- stations, bumps and junctions with their resolved settings;
- per station, the nearest speaker position;
- per junction, the nearest station block used for priority;
- the reverse path from the end rail, so a terminus turn-around switches to a
  baked path instead of walking rails from the lead.

Baking runs `RailPath.build` as now, at spawn, at circulate and on
`/metro rebake [line|all]`. These may load chunks while they walk; that cost
is paid once, not per tick. A terminus turn-around whose reverse path is
missing bakes it the same way, once. Other bakes that start during a tick
(dirty rebakes, a wave path) never load chunks: a walk that reaches an
unloaded chunk keeps the old path and retries later.

Each baked path keeps the set of chunks it runs through. A mixin on
`ServerWorld.onBlockStateChanged` marks a path dirty when a rail, marker,
speaker or junction block changes in one of those chunks; dirty paths rebake
at most once a second. Block entity edits through `MetroNetworking` and the
`/metro station` commands mark the path dirty directly. Blocks in unloaded
chunks cannot change, so a baked path stays correct while nobody is near it.

On rebake a running train keeps its place by mapping its head to the same
rail node on the new path. If that node is gone (track removed under the
train), the train stops and is reported in `/metro list`.

## Views

Each tick, for each train, sample every car's arc. If the car's chunk has its
entities loaded (`isChunkLoaded`), even outside simulation distance, make sure a `MetroCarEntity` exists for it, then seat it as
`applyCarPositions` does now. If the chunk does not, discard the view.

`TrainTickets` holds a radius-2 `TRAIN` ticket, which entity-ticks only the
centre chunk, on every chunk a car occupies. A ticket stays 100 ticks after
the last car leaves its chunk, so a train does not swap tickets at each
border. Tickets are never serialized.

A view keeps the same entity when it crosses a chunk border, so the client
interpolates it smoothly and sounds attached to it keep playing.

- Views never save with the chunk (`shouldSave` false); the state is the only
  copy, so there is nothing stale to reconcile.
- A player rider keeps the chunk loaded, so a car carrying a player never
  despawns.
- A non-player rider (mob, minecart) is dismounted at the car's position when
  its view despawns and stays in that chunk. Carrying mobs across unloaded
  track is out of scope.
- A view spawning onto a chunk does not restore the riders it had before.

## World-dependent work

Work that needs the world runs only when the relevant position is loaded:

- arrival, departure and incoming sounds, played only when a player is in
  range, using the baked speaker position;
- HUD fields and announcements, written onto views when they exist;
- passenger re-seating.

Train-on-train blocking and junction priority use the saved data for every
train: other trains' car arcs map to rail nodes, then to arcs on our path via
`arcsOnTrack`. This replaces the entity scan in `blockingTrainHoldArc` and
the lead-entity distance in `hasJunctionPriority`, so the rules are the same
loaded or not.

## Migration

Old cars carry `ConsistId`, `ArcLength` and the path identity. When an old car
loads (`ServerEntityEvents.ENTITY_LOAD`) and its consist id is not in the
state, bake its path from the saved origin and heading, as `reconstruct` does
now, and add a train record. Then discard the old car; the view logic spawns
a fresh one. Cars of one consist that load later are discarded. Trains in
chunks nobody visits migrate the first time those chunks load.

## Removed

- `updateForcedChunks` and `chunkRadius`.
- `reconcile`, `reconstruct`, `carsThisTick` and the periodic marker rescan.
- Per-car persisted arc, yaw and path identity, once migration has run.

## Open questions

- Dismount non-player riders in place, or keep their data and re-add them when
  the view respawns?
- Should `/metro remove` and spawner left-click work on trains with no loaded
  view (by line or nearest arc), or only on visible ones?
