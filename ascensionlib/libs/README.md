WebGUI (https://github.com/mc-webgui/webgui, MIT) is compiled against and loaded by the dev server, but its jar is not committed.
Download `webgui-fabric-2.0.0+mc1.21.1.jar` from https://github.com/mc-webgui/webgui/releases/tag/v2.0.0 into this folder
(`gh release download v2.0.0 -R mc-webgui/webgui -p 'webgui-fabric-2.0.0+mc1.21.1.jar' -D ascensionlib/libs`).

Then run `python -I tools/prepare_webgui_dev_jar.py`. It writes `webgui-dev.jar` (what Gradle uses), a local copy without the
`Fabric-Loom-Version` manifest line, because WebGUI is built with a newer Loom than this project can remap.

Polymer (https://github.com/Patbox/polymer, LGPLv3) is compiled against only, to add the item art to the server resource pack when Polymer is
installed. Put `polymer-resource-pack-0.9.19+1.21.1.jar` and `polymer-common-0.9.19+1.21.1.jar` in this folder: download them from
`https://maven.nucleoid.xyz/eu/pb4/<artifact>/0.9.19%2B1.21.1/` or unzip them from `META-INF/jars/` inside `polymer-bundled-0.9.19+1.21.1.jar`.

Cobblemon Project Fusion (https://www.curseforge.com/minecraft/mc-mods/cobblemon-project-fusion, All Rights Reserved) is compiled against
only, never shipped, for the Fuser bond (`com.ascensionlib.compat.bond`). Download `Cobblemon-Project-Fusion-fabric-2.1.0.jar` from its
CurseForge Files page into this folder and rename it `project-fusion-fabric-2.1.0.jar`. The bond code is loaded only when the mod is installed.
