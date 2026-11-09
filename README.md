# SeedVeil

**A Paper plugin that makes your world seed much harder to crack with tools such as SeedCrackerX.**

SeedVeil is a single jar with no dependencies (no ProtocolLib or PacketEvents). It combines five layers:
a fake hashed seed in the login packets, a per-server bedrock pattern, random structure and feature seeds,
a block on seed-revealing commands and a structure-visit monitor that alerts staff.

**Download:** [Hangar](https://hangar.papermc.io/mrpunky/SeedVeil) · [GitHub Releases](https://github.com/craftavia/SeedVeil/releases)

## Features

| Layer | What it does | When it takes effect |
|---|---|---|
| **Hashed seed** | Replaces the hashed seed in the login and respawn packets with a new random value every time. Seed crackers use this value to confirm candidate seeds; with a fake value that check gives nothing. The client only uses it for biome colour blending, so players see no real difference. | Immediately, for every player |
| **Bedrock data pack** | On every startup reads the vanilla world generation files from the server jar, replaces the `random_name` of the bedrock floor/roof rules (optionally deepslate too) with a name that contains a secret per-server token, and loads the result as a data pack. The bedrock pattern can no longer be computed from the seed; terrain and biomes stay the same. Handles both 26.2 (`noise_settings`) and 26.3 (`worldgen/material_rule`). | Newly generated chunks only |
| **Structure seeds** | `/seedveil scramble` randomises the 21 `seed-*` keys in `spigot.yml` (villages, temples, slime chunks, strongholds...), so structure positions no longer follow the seed. | After a restart, new chunks only |
| **Feature seeds** | The same command turns on Paper's `feature-seeds.generate-random-seeds-for-all`, so ores, dungeons and trees get their own seeds. Even a leaked seed does not give diamond positions. | After a restart, new chunks only |
| **Command block** | Blocks `/seed`, `/minecraft:seed`, `/mv info` and any command you add, and hides them from tab completion. Repeated attempts are reported to staff. | Immediately |
| **Structure monitor** | Alerts staff when a player visits many of the structure types seed crackers rely on (igloos, desert/jungle temples, swamp huts, shipwrecks, outposts, monuments) in a short time. It never punishes anyone. | Immediately |

Also:

- `/seedveil status` shows the real state of every layer (and whether Paper's Anti-Xray is on), and the same summary is printed to the console at startup.
- Messages follow each player's client language. English and Turkish are bundled; add a language by dropping a
  `messages_<code>.properties` file in the plugin folder.
- If the hashed seed layer cannot hook into the server (for example on an unsupported version), it turns itself
  off and logs a warning. It never kicks players; the real packet is sent instead.
- No network calls, no metrics, no update checker.

## Requirements

| | |
|---|---|
| Server | Paper or Purpur **26.2 and 26.3** (tested). Other forks and versions are untested. Folia is not supported. |
| Java | 25 |
| Dependencies | None. Geyser is optional (see notes). |

## Installation

The generation layers (bedrock, structure and feature seeds) cannot change chunks that already exist. For full
protection install SeedVeil **before** the world is generated. Recommended order for a new world:

1. Put `SeedVeil-x.y.z.jar` in `plugins/` and start the server once. This creates the config, the messages,
   the bedrock data pack and the secret `plugins/SeedVeil/bedrock-token.txt`.
2. Run `seedveil scramble` in the console. On a small world it applies right away; on a larger one it asks for
   `seedveil scramble confirm`.
3. Stop the server, delete the world folder(s) and start it again. On 26.x all dimensions live inside `world/`
   (`world/dimensions/...`); older layouts also have `world_nether` and `world_the_end`. Leave `level-seed` in
   `server.properties` empty (or set a new, unpublished value) so the new world does not reuse a known seed.
4. Run `/seedveil status` and check that every line is green.

On an existing world, the hashed seed, command block and structure monitor protect you immediately. The
generation layers only apply to chunks generated after they are turned on, typically at the next world reset.

## Commands and permissions

| Command | Description | Permission | Default |
|---|---|---|---|
| `/seedveil` or `/seedveil status` | Shows the state of every layer | `seedveil.admin` | op |
| `/seedveil scramble` | Randomises the `spigot.yml` structure seeds and turns on Paper feature seeds (restart needed). Asks for confirmation if the worlds already have many region files. | `seedveil.admin` | op |
| `/seedveil scramble confirm` | Applies the scramble on a world that is already in use | `seedveil.admin` | op |
| `/seedveil reload` | Reloads `config.yml` and the messages (the `bedrock-datapack` section needs a restart) | `seedveil.admin` | op |

| Permission | Description | Default |
|---|---|---|
| `seedveil.admin` | Use `/seedveil` | op |
| `seedveil.bypass` | Use the blocked commands and skip the structure monitor | false |
| `seedveil.notify` | Receive the command-probe and structure-monitor alerts | op |

## Configuration

```yaml
default-language: en

hashed-seed:
  enabled: true

bedrock-datapack:
  enabled: true
  randomize-deepslate: false   # also randomise the deepslate transition pattern

commands:
  enabled: true
  blocked: [seed, mv info, mvinfo, mvi]   # namespaced forms (/minecraft:seed) are matched automatically
  probe-alert:
    threshold: 5
    window-seconds: 30

structure-monitor:
  enabled: true
  threshold: 5          # different structures...
  window-minutes: 30    # ...within this time
  structures: [minecraft:igloo, minecraft:desert_pyramid, minecraft:jungle_pyramid, minecraft:swamp_hut,
               minecraft:shipwreck, minecraft:shipwreck_beached, minecraft:pillager_outpost, minecraft:monument]

scramble:
  max-region-files-without-confirm: 16
```

The full file with comments is created on first start.

## Notes and limitations

- **It raises the cost of seed cracking; it is not a guarantee.** Biome layout and End pillar heights still
  follow the seed. On their own they do not give a practical way to crack it, but they are not hidden.
- **Version-specific code.** The hashed seed layer uses server internals (NMS). Each new Minecraft version may
  need a SeedVeil update. It is tested on Paper/Purpur 26.2 and 26.3; 1.21.x is not supported.
- **Geyser.** Geyser does not send the world seed to Bedrock clients, so the hashed seed layer only matters for
  Java players. The generation layers still matter for everyone, because anything a player can see in the world
  can be fed into a cracker.
- **Keep `bedrock-token.txt` secret and do not delete it.** It is created per server and never leaves your
  server. A new token changes the bedrock pattern of chunks generated after it, which leaves visible seams.
- **Backups and `level.dat` contain the real seed.** Restrict who can download world files.
- **Command block** covers players only, not the console, command blocks or data pack functions. `/locate` is not
  blocked by default; add it to `commands.blocked` if you want to.
- **Scramble** edits `spigot.yml` and `config/paper-world-defaults.yml` in the server directory (default locations;
  custom `--spigot-settings` / `--paper-settings-directory` paths are not detected). On a used world, existing chunks
  stay vanilla and structures can be cut off where old and new chunks meet.
- **Structure monitor** only alerts. Normal explorers can trigger it too; tune `threshold` and `window-minutes`.

Made by the Craftavia team, used on play.craftavia.net, https://craftavia.net

## Building

```bash
./gradlew build
```

The build uses [paperweight-userdev](https://github.com/PaperMC/paperweight); the first run downloads the Paper
26.2 dev bundle from `repo.papermc.io` and prepares the server classes, which takes a few minutes. The jar is
written to `build/libs/`.

## Türkçe

**İndir:** [Hangar](https://hangar.papermc.io/mrpunky/SeedVeil) · [GitHub Releases](https://github.com/craftavia/SeedVeil/releases)

SeedVeil, dünya seed'inin SeedCrackerX gibi araçlarla kırılmasını ciddi ölçüde zorlaştıran bir Paper eklentisidir.
Tek jar, ek bağımlılık yok. Beş katmanı vardır:

- **Hashed seed:** Giriş ve yeniden doğuş paketlerindeki seed özetini her seferinde rastgele bir değerle değiştirir. Hemen etkilidir.
- **Bedrock datapack:** Bedrock taban/tavan desenini sunucuya özel gizli bir anahtarla değiştirir (`bedrock-token.txt`). Yalnızca yeni üretilen chunk'larda etkilidir.
- **Yapı seed'leri:** `/seedveil scramble`, `spigot.yml` içindeki `seed-*` anahtarlarını rastgele yapar. Yeniden başlatma sonrası, yeni chunk'larda etkilidir.
- **Feature seed'leri:** Aynı komut Paper'ın `feature-seeds.generate-random-seeds-for-all` ayarını açar (cevher, dungeon, ağaç). Yeniden başlatma sonrası, yeni chunk'larda etkilidir.
- **Komut engeli ve yapı takibi:** `/seed` gibi komutları engeller, şüpheli yapı gezintisini yetkililere bildirir. Ceza vermez. Hemen etkilidir.

Yeni dünya için önerilen kurulum:

1. Jar'ı `plugins/` klasörüne atın ve sunucuyu bir kez başlatın.
2. Konsoldan `seedveil scramble` yazın (dünya büyükse `seedveil scramble confirm` ister).
3. Sunucuyu durdurun, dünya klasörünü silin (26.x'te tüm boyutlar `world/` içindedir) ve tekrar başlatın. `server.properties` içindeki `level-seed` boş kalsın ya da yeni, paylaşılmamış bir değer olsun.
4. `/seedveil status` ile her satırın yeşil olduğunu kontrol edin.

Mevcut bir dünyada hashed seed, komut engeli ve yapı takibi hemen korur; üretim katmanları yalnızca bundan sonra
üretilen chunk'ları etkiler. Paper/Purpur 26.2 ve 26.3'te test edildi; her yeni Minecraft sürümü güncelleme
gerektirebilir. Eklenti seed kırmayı zorlaştırır, kesin garanti vermez. Oyun dili Türkçe olan oyuncular mesajları
Türkçe görür (`messages_tr.properties`).

Craftavia ekibi tarafından geliştirildi, play.craftavia.net sunucusunda kullanılıyor: https://craftavia.net

## License

[MIT](LICENSE) © Craftavia
