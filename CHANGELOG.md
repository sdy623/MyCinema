# Changelog

## 0.1.0 — First public release

MyCinema turns Minecraft Java 1.21.1 into an operable cinema rather than a decorative build.

### Cinema operation

- Film catalogue with reusable media sources, posters, and ordered multi-clip programmes.
- Dated auditorium schedules with server-side automatic start, completion, conflict, and recovery handling.
- TMS, manager terminal, tablet, staff permissions, emergency access, and redstone scene outputs.
- Public information panels, lobby programmes, advertising surfaces, posters, seating, and staff doors.
- Seven bilingual Ponder tutorials for the Control Terminal, TMS, both projector types, information panels, seat guides, and staff readers.

### Projection and playback

- Shared auditorium playback with play, pause, stop, seek, clip changes, reconnect recovery, and decoder cleanup.
- MyCinema-owned `mycinema:tms_terminal` cabinet with Media, Projector Group, and Audio Origin controls.
- Separate `mycinema:cinema_projector` and `mycinema:imax_gt_projector` registrations, each with its own cabinet and inventory artwork; IMAX GT places parallel cabinets as one model-shaped multiblock, preserves the visible floor, and removes as one device.
- Projector groups, picture mirroring, geometry controls, screen modes, and cinema-scoped audio behavior.
- Real H.264/AAC playback through the pinned official WaterFrames and WaterMedia releases.

### Distribution boundary

- MyCinema source is Apache-2.0.
- WaterFrames 3.0.0-alpha.1, WaterMedia 3.0.0, and WaterMedia Binaries 3.0.0 remain unmodified external dependencies.
- WaterUI is supplied by the official WaterFrames artifact and is not bundled in the MyCinema JAR.
- Ponder 1.0.87 and Flywheel 1.0.4 remain unmodified external client dependencies and are not bundled in the MyCinema JAR.
- The release contains no repackaged third-party JARs or upstream classes.

### Release verification

- 48 reviewed GameTest classes: 241 required runtime cases, 0 failures.
- Two-start dedicated-server persistence and shutdown verification.
- Clean-client H.264/AAC playback, pause, seek, synchronized displays, reconnect, stop, and resource-release verification.
- Final repository and JAR compliance checks.
