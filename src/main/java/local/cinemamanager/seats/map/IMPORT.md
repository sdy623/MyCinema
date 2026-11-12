# Authored seat-map import (candidate, source-only verification)

This read-only map displays only entries already in the chosen hall's seat registry at their actual canonical BlockPos. It never creates chairs, numbers seats, adopts a physical UID, scans the world, loads chunks, or invents routes. Serviceability, physical binding, occupancy and admission are separate; admission is always UNKNOWN in this version.

Without a layout, the viewport shows actual registered positions with empty floor/section/aisle/entrance metadata. Overlapping heights are not guessed into named floors.

## Import workflow

1. Finish or select an explicit geometry document with version 1 and the exact target scope `{controllerId, hallId, dimension}`. The provided map-builder template has null controller/hall placeholders and must not be treated as imported or ready until those values are filled from the actual intended controller/hall.
2. Each assignment is `{position, seatUid, floorId, zoneId}`. `position` is the signed packed canonical Minecraft BlockPos long; `seatUid` is the installed chair's physical UID. Assignments may include authored chairs that are not yet registered. Each currently registered chair must have an exact position+UID match; missing or replaced assignments fail closed. No registration or numbering changes occur during import.
3. Place the UTF-8 JSON at `<world>/mycinema-seat-maps/<controller UUID>/<hall UUID>.json`. This file is read-only input; the importer never rewrites it. Symlinks in the dedicated root, controller directory or final document are rejected. There is no arbitrary filename/path command argument.
4. Stand within eight blocks of the actual manager as its current owner or an authorized operator, then run:

   `/mycinema_seat_map import <managerX> <managerY> <managerZ> <hallUUID> <currentRegistryRevision> <currentLayoutRevision>`

   For the first authored layout, currentLayoutRevision is 0 and the document's layout.revision must be 1. A later import requires the current revision and document revision exactly one greater. The existing seat-management screen reports the registry revision; a successful map header reports geometry revision. No console/remote authority bypass is provided.
5. Explicitly refresh the manager or bound information-panel Seats view. Import does not silently replace a viewer's already-complete snapshot.

The command reports a localized success/error. Any error leaves prior stored geometry unchanged. Unknown/newer saved data is preserved and blocks replacement, rather than being reset or overwritten. JSON is limited to 1 MiB, strict UTF-8, exact known fields, unique object keys and no trailing value. Floats are rejected for integer fields.

## Geometry semantics

`layout` contains `revision`, `bounds`, `floors`, `zones`, `entrances`, `aisles`. `transform` contains `worldOrigin`, `orientation` (NORTH/EAST/SOUTH/WEST), `scale`. Bounds and polygons are actual authored world geometry. Floors explicitly include minY/maxY. Spatial zones/sections are independent of a registry seat's numbering-group label: a whole numbered row may cross manually defined Left/Centre/Right sections without renumbering or relabeling the registry.

An entrance's floor/zone identifies the entrance's actual position. Explicit `seatIds` or `rows` associations may target another authored floor (for example lower lobby to tiered hall); this is a manual association, not a discovered path or proof of traversability. Future/unregistered target rows resolve only when matching registered seats exist. Do not use a physical seat UID as an entrance's `seatIds` registry entry ID. Prefer explicit row associations when registry IDs do not yet exist.

Rendered pages carry opaque display keys and map-local coordinates only; public projection contains no controller/hall/registry/physical UID, owner/player identity, media path or access token. Existing information and wall-information blocks offer the guest view only when explicitly bound to a valid HALL/controller. Advertising blocks do not authorize this viewer.

## Delivery and limits

A snapshot is pre-serialized in full before page zero is sent: 64 seats/page, at most 64 pages, less than 48 KiB actual UTF-8 JSON per packet (256 bytes reserved for envelope), at most 1 MiB total. Large authored headers fail with map_too_large rather than stripping geometry or sending a partial map. One snapshot per viewer; 16 sessions and 8 MiB server-wide; 60-second TTL. Every page rechecks current source identity, reach, manager permission or exact public-panel binding, and hall. A fresh request invalidates the old server snapshot. Client cancellation is scoped to its request and source so an old cancellation cannot remove a new request.

Compilation, pure checks, server GameTests and client runtime checks remain to be run by the coordinating test process; this document does not claim them passed.
