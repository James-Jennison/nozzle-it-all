# Nozzle It All brand assets

Derived from the Lovart.ai brand export (2026-09-25; the raster boards are not stored in the repo). The "Infill" mark (a nozzle above a rhombus infill grid) was traced from the exported PNG into clean vector polygons.

| File | Use |
|---|---|
| `mark-black.svg`, `mark-white.svg`, `mark-violet.svg` | The mark alone, single colour |
| `app-icon-512.svg`, `play-icon-512.png` | Google Play icon (white mark on `#8B5CF6`) |
| `favicon.svg`, `favicon-*.png` | Web favicon (violet mark on `#0D1114`); copies in `site/` |

Palette: violet `#8B5CF6` (fills only), print orange `#F2754E`, background `#0D1114`, surfaces `#14191D` / `#1E2429`, text `#EEF2F4` / muted `#8A959C`. **Text-safe violet: `#A78BFA` on dark (6.97:1), `#6D28D9` on light (7.1:1 with white text).** Raw `#8B5CF6` is 4.48:1 on the dark background and 4.23:1 under white text, so do not use it for small text.
The Android launcher icon is `app/src/main/res/drawable/ic_launcher*.xml` (adaptive, with a monochrome themed layer).
