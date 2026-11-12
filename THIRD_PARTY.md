# Third-party dependency policy

MyCinema source code is Apache-2.0. Its runtime dependency graph is **not fully open source** because WaterFrames is All Rights Reserved and WaterMedia uses a non-commercial source-available license. Users must obtain those components separately from their official channels.

| Component | Required version for this migration | How MyCinema uses it | Distribution rule |
|---|---:|---|---|
| Minecraft: Java Edition | 1.21.1 | Host game | Never redistribute game files. |
| NeoForge | 21.1.244 | Mod loader/API | Resolve through the official build tooling. |
| WaterFrames / WaterUI | WaterFrames 3.0.0-alpha.1, NeoForge 1.21.1 | External display API and runtime mod; the official WaterFrames file supplies WaterUI | All Rights Reserved. Do not modify, embed, shade, or redistribute the official file with MyCinema. |
| WaterMedia | 3.0.0 | External media API and runtime mod | DPS (Non-Commercial). A dependent mod must not embed, bundle, or redistribute WaterMedia. |
| WaterMedia: Binaries | 3.0.0 | Client media engines | Install separately. Do not bundle it. |
| Ponder | 1.0.87+mc1.21.1 | Client-side interactive tutorial engine | MIT. Resolve from the Creators of Create Maven; do not embed it in the MyCinema JAR. |
| Flywheel | 1.0.4 | Client rendering runtime required transitively by Ponder | MIT. Install through Ponder's declared dependency; do not embed it in the MyCinema JAR. |
| Iris / Sodium | optional bridge only | Optional client rendering integration | Not included in the core repository or release. |

Exact official artifacts used for compatibility testing:

- [WaterFrames](https://modrinth.com/mod/waterframes) Modrinth project `eBzFuVTM`, version `3q7i6sG3`.
- [WaterMedia](https://modrinth.com/mod/watermedia) Modrinth project `G922NeHS`, version `7aLfBD61`.
- [WaterMedia Binaries](https://modrinth.com/mod/watermedia-binaries) Modrinth project `4997XcoK`, version `IvgVb1Pv`.
- [Ponder](https://github.com/Creators-of-Create/Ponder) Maven coordinate `net.createmod.ponder:ponder-neoforge:1.0.87+mc1.21.1`.
- [Flywheel](https://github.com/Engine-Room/Flywheel) Maven coordinate `dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.4` (transitive runtime dependency of Ponder).

These exact releases were published together on 2026-11-02 and satisfy WaterFrames 3.0.0-alpha.1's declared dependency range. WaterUI is present in that official WaterFrames artifact and has no separate Modrinth project. MyCinema resolves the official artifacts from Modrinth and never embeds or republishes their JARs.

Ponder and Flywheel are resolved from the Creators of Create Maven and remain separate MIT-licensed runtime components.

WaterMedia and WaterMedia Binaries copyright SrRapero720
(https://github.com/SrRapero720). Their full license and bundled third-party
notices remain in the separately obtained official artifacts.
