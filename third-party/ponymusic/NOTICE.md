# PonyMusic vinyl artwork

Source: https://github.com/wangchenyan/ponymusic/tree/f3c502c9e9d448174c644d77dc0492c7b59d555b

Copyright 2024 wangchenyan. The upstream README declares the Apache License, Version 2.0.
See [Apache-2.0.txt](Apache-2.0.txt) for the license.

Adapted source:
- app/src/main/java/me/wcy/music/widget/AlbumCoverView.kt

Upstream artwork included without visual modification:
- app/src/main/res/drawable-xxhdpi/bg_playing_disc.webp
- app/src/main/res/drawable-xxhdpi/ic_playing_needle.webp

PureBits changes: port the artwork layout and animation to Compose, adapt sizing
to the existing artwork slot, reuse existing artwork requests and playback state,
and stop animation on lifecycle/reduced-motion conditions. No playback code from
PonyMusic is included.

The upstream README provides a project-level Apache-2.0 notice but does not
separately identify the authorship or provenance of the two image assets.
Confirm asset redistribution rights before publishing a release containing them.
