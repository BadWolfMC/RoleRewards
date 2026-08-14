# RoleRewards

RoleRewards is a small Paper plugin for granting scheduled rewards to members of a LuckPerms group. It was designed for a single authoritative Paper server and intentionally keeps reward delivery generic: each reward is just a LuckPerms group, a calendar schedule, and one or more console commands.

## Requirements

- Paper 26.2+
- Java 25
- LuckPerms 5.x

RoleRewards has no hard dependency on CMI, PlayerPoints, PlaceholderAPI, Continuum, or an external database. SQLite is bundled into the plugin JAR and stored at `plugins/RoleRewards/rolerewards.db`.

## Build

The project uses Gradle Kotlin DSL and a Java 25 toolchain. If this checkout does not yet contain the Gradle Wrapper, generate the standard wrapper once with an installed Gradle:

```bash
gradle wrapper --gradle-version 9.7.0
```

Commit the generated `gradlew`, `gradlew.bat`, and `gradle/wrapper/` files, then build normally:

```bash
./gradlew build
```

On Windows PowerShell, use `./gradlew.bat build`. The Foojay toolchain resolver is configured so Gradle can provision a Java 25 toolchain when necessary.

The deployable shaded JAR is written to `build/libs/RoleRewards-<version>.jar`.

## First-run commissioning

The default Companion reward is present but its automatic schedule is disabled. This is deliberate.

1. Install RoleRewards on the single Paper server that should be authoritative for rewards.
2. Confirm LuckPerms is installed and the configured group exists.
3. Start the server and inspect `plugins/RoleRewards/config.yml`.
4. Use `/rolerewards preview companion` to verify the membership list.
5. If desired, use `/rolerewards run companion` to test a real current-period reward. **A manual run creates that month's immutable recipient snapshot even if zero members are eligible**, so do not use it merely as a dry run.
6. Set `schedule.enabled: true` only when automatic execution is wanted, then `/rolerewards reload`.

If automatic scheduling is enabled after the configured date/time has already passed for the current month, that month is considered due and will run on the next scheduler check unless a run snapshot already exists.

## Configuration

```yaml
timezone: "America/New_York"
scheduler-check-minutes: 5

rewards:
  companion:
    group: "companion"
    membership:
      direct-only: true

    schedule:
      enabled: false
      day-of-month: 1
      time: "06:00"

    commands:
      - "points give {player} 50"
```

RoleRewards v1 intentionally supports **direct LuckPerms group membership only**, matching `lp group <group> listmembers`. A configured `direct-only: false` is rejected rather than silently changing eligibility semantics.

The configured day is clamped to the last valid day of shorter months. For example, day 31 runs on February's final day.

### Command placeholders

Each console command supports:

- `{player}` — last known LuckPerms username
- `{uuid}` — player UUID
- `{reward}` — configured reward ID
- `{period}` — calendar period such as `2026-08`

A leading `/` in a configured console command is accepted and stripped before dispatch.

Multiple commands are supported. RoleRewards records the next command index after each accepted command, so a normal retry resumes at the failed command instead of replaying earlier successful commands. If a period has failed grants, review them before changing that reward's command list; retries use the current configuration together with the stored command index.

## Scheduling and duplicate protection

RoleRewards works in calendar periods (`YYYY-MM`) rather than fixed 30-day intervals.

For each reward and month, SQLite stores:

- one period-level run snapshot;
- one grant row per eligible UUID;
- grant status (`PENDING`, `GRANTED`, or `FAILED`);
- the next command index for safe normal retries;
- timestamps and failure details.

The `(reward_id, period)` run key and `(reward_id, period, player_uuid)` grant key are unique. A server restart at the scheduled minute therefore does not skip the month, while repeated scheduler checks cannot create a second run snapshot.

If the JVM/server is interrupted while a grant is `PENDING`, RoleRewards converts that grant to `FAILED` on next startup for manual review. Because console commands are external side effects, no plugin can guarantee exactly-once behavior across a hard crash between command execution and the SQLite update. Verify an interrupted grant before manually retrying it.

## Commands

All command permissions default to **OP**. Console and RCON are authorized. Permissions are also checked inside command handlers, not only in the visible Brigadier command tree.

| Command | Permission | Purpose |
|---|---|---|
| `/rolerewards status` | `rolerewards.status` | Show schedule and last-run status |
| `/rolerewards preview <reward>` | `rolerewards.preview` | Show current eligible members and current-period state |
| `/rolerewards run <reward>` | `rolerewards.run` | Manually create/run the current period snapshot |
| `/rolerewards retry <reward>` | `rolerewards.retry` | Retry failed grants in the current period |
| `/rolerewards history` | `rolerewards.history` | Show your own reward history |
| `/rolerewards history <player>` | `rolerewards.history.others` | Show another player's reward history |
| `/rolerewards reload` | `rolerewards.reload` | Reload config/messages and scheduler interval |

`rolerewards.admin` grants all RoleRewards command permissions.

The `/rr` alias is also registered.

Command/subcommand visibility is permission-aware. Reward IDs tab-complete dynamically from `config.yml`, while history player names are suggested from SQLite history plus currently online players, so offline recipients remain discoverable without querying LuckPerms every time Tab is pressed.

## Messages

User-facing plugin messages are stored in `messages.yml` and parsed with Paper's bundled Adventure MiniMessage implementation. The full standard MiniMessage tag set is available, including colors, hex colors, gradients, decorations, hover/click events, fonts, and the other standard tags supported by the server's Adventure version.

Dynamic values are inserted with MiniMessage resolvers rather than reparsed as markup.

RoleRewards does not register a player login listener. Offline-capable reward commands run at reward time; players can use history (where permitted) to verify grants.

## Data files

```text
plugins/RoleRewards/
├── config.yml
├── messages.yml
└── rolerewards.db
```

Back up `rolerewards.db` with the rest of the server plugin data if grant history should be retained.
