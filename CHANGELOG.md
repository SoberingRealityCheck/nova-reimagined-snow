# Changelog

## 1.0.3 — Build and packaging fixes

- Excluded `log4j2.xml` from the production jar. This file is used during development
  to route `compsnow` log output to a dedicated file; shipping it overrode end-users'
  game-wide logging configuration unintentionally.
- Pinned `fabric-loom` to a stable release (was `1.7-SNAPSHOT`).
- Corrected `fabricloader` minimum version in `fabric.mod.json` to `>=0.16.5`
  (was `>=0.15.0`, inconsistent with the actual project target).
- Added `issues` URL to `fabric.mod.json`.

---

## 1.0.2 — Anchor fix

Player-relative texture anchor encoding extended to 11-bit precision (mod 2048) to
support texture sizes ≥ 512 chunks. The previous mod-256 encoding caused incorrect
texel lookups at large DH render distances where the texture size exceeded 256 chunks.

The meta texture channel layout was updated accordingly:
- G channel: low 8 bits of anchor X mod 2048
- B channel: low 8 bits of anchor Z mod 2048
- A channel: active flag (bit 7) + high 3 bits of anchor X (bits 4-6) + high 3 bits
  of anchor Z (bits 1-3)

The maximum texture size was raised to 2048×2048 chunks to cover DH render distances
up to 1024 chunks with margin.

---

## 1.0.1 — Initial public release

### Core features

- Per-block-column biome snow eligibility texture (`compsnow:snow_biome_map`) updated
  each tick. Player-relative anchor prevents chunk coordinate aliasing on large worlds.
- Meta texture (`compsnow:snow_biome_meta`) encodes texture size and anchor position
  for shader-side decoding.
- Texture auto-sizes to cover the player's current render distance and reallocates on
  mid-session render distance changes.
- Correctly suppresses snow tinting in the Nether and End.
- Snow variant textures (`compsnow:snow_variant_0` through `compsnow:snow_variant_3`):
  four configurable snow surface textures resolved from the active resource pack stack.
  Auto mode and Manual_Override mode. Empty slots fall back to vanilla snow.
- DH LOD material textures: 8 grass variants plus snow, dirt, stone, deepslate, and
  sand slots. Configured grass paths that resolve successfully are automatically
  propagated across all 8 shader slots via modulo if fewer than 8 are supplied.
- Season Cache integration via optional reflection bridge. When Season Cache is
  present, its server-authoritative per-chunk snow coverage drives the eligibility
  texture instead of the client-side BiomeSampler.
- Dedicated DH chunk listener: `DhApiChunkModifiedEvent` triggers re-sampling when
  DH regenerates LODs.
- Client-side only — no server-side installation required.
- Dedicated `compsnow.log` output file available during development via `log4j2.xml`.
