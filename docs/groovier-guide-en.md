# Groovier User Guide

> **Positioning**: a heavy-duty **compatibility repair tool** for mod developers and modpack maintainers.
> It is not a content framework — for recipes and items, use KubeJS. Groovier handles what KubeJS cannot reach: directly modifying the Java runtime of the game and other mods.
> This guide ships inside the mod jar; run `/gvr help` to export it to `local/groovier-guide.md`.

**Server-side only for now**, and it modifies live bytecode — back up your world before doing anything.

---

## 1. What It Can Do

| Capability | One-liner | How |
|---|---|---|
| Groovy scripts | Run on world load; listen to events, call any game/mod API | Drop `.groovy` files into `groovy_scripts/` |
| Method pins (Pins) | Intercept any method of any class: replace the result before it executes, or listen after | `Pins.declare / override / on` |
| Mixin surgery (Surgery) | Strip broken mixins off a class, or rewrite a method body directly | `Surgery.pre / submit` |
| Whole-class override | Rewrite an entire class in Groovy and replace the original | `groovy_scripts/override/` |
| Mixin removal | Bulk-remove mixins via config file | `config/groovier-mixin-blacklist.txt` |
| KubeJS bridge | Script intercepts runtime behavior → notify KubeJS scripts to react | `Events.fire` |

## 2. Where Files Go

```text
groovy_scripts/                  Your scripts (the main battlefield)
  ├─ phase.groovy                Top-level scripts: run in relative-path order on world load
  ├─ lib/utils.groovy            Organize subdirectories freely
  ├─ override/                   [RESERVED] whole-class override sources, not run as normal scripts
  └─ refer/                      [RESERVED] decompiled output (.java/.txt), not scanned
config/
  ├─ groovier-mixin-blacklist.txt   Mixin removal rules (§9)
  ├─ groovier-postwatch.txt         Watched classes: bytecode saved before/after changes, for manual diffing
  ├─ groovier-refer.txt             Capture target classes and decompile into source templates (§11)
  └─ groovier-override.txt          Whole-class override targets (§10)
local/                           All generated artifacts — safe to delete and rebuild
logs/groovier.log                Main log (cleared every launch) — look here first when something breaks
```

- Only lowercase `.groovy` counts; **the relative path is the script's identity** (the on/off key and cache key both use it) — moving or renaming = a brand-new script;
- Config files are UTF-8, `#` comments, blank lines ignored; blacklist/postwatch/refer **require a restart** to take effect; malformed entries only WARN and skip — they never crash the launch;
- **Do not load scripts from sources you don't trust** (§12).

## 3. Quick Start

Example: `groovy_scripts/hello.groovy`

```groovy
Log.info("Hello! Level class = {}", Level.class.simpleName)

int n = 0
EventManager.listen { ServerTickEvent.Post event ->
    if (++n % 200 == 0) Log.info("tick #{}", event.server.tickCount)
}
```

Enter a world (or `/groovier reload`) and check `logs/groovier.log`.

## 4. Script Basics

### 4.1 When Scripts Run

- **On world load (ServerStarting)** — nothing runs at the main menu; you must enter a world to test;
- Scripts run in relative-path order; all scripts in one round share a class loader and can reference each other's classes;
- `/groovier reload`: unregister all listeners and pin callbacks → recompile (unchanged scripts are served from cache) → re-run; globals survive;
- `/gvr disable <script>`: immediately unregister everything that script registered — no restart needed.

### 4.2 What's Available in Scripts

| Binding | Description |
|---|---|
| `Log` | Logging, to `logs/groovier.log` + console |
| `EventManager` | Listen to NeoForge events (§4.3) |
| `globals` | Global variables: shared across scripts, survive reload, persist to disk (§4.4) |
| `Events` | Fire custom events to KubeJS (§6) |
| `Surgery` | Surgery API (§9) |
| `Pins` | Pin API (§8) |

Pre-imported classes (import anything else yourself): `Level`, `Block`, `Item`, `ItemStack`, `BlockPos`, `CompoundTag`, `ResourceLocation`, `ServerPlayer`, `Entity`, `Player`, `ServerTickEvent`, `PlayerEvent`.

### 4.3 How to Listen to Events

Pattern: `EventManager.listen { EventType event -> ... }`. **The type of the closure's single parameter selects the event** — any NeoForge event works; `import` it first:

```groovy
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent

EventManager.listen { LivingDeathEvent e ->
    Log.info("{} died", e.entity.name.string)
}
```

**Pitfalls**:

- Only **concrete event classes** can be listened to. Abstract parents don't work — for example `ServerTickEvent` requires its subclass `ServerTickEvent.Pre` or `.Post`;
- The closure must have exactly one parameter, whose type must be an `Event` subclass, or registration is rejected (logged as an error);
- Listeners belong to the script that registered them: disabling/reloading the script unregisters them too. Nothing leaks.

### 4.4 globals — Global Variables

```groovy
globals.set('hardMode', true)
def mode = globals.get('hardMode') ?: false
globals.remove('hardMode')
```

- Shared across scripts and kept across `/groovier reload`; keys whose values are String/Number/Boolean/List/Map are auto-persisted (`local/globals.json`) and survive restarts;
- Keys starting with `groovier.` are never persisted (the on/off keys are session-scoped; a restart returns everything to default-enabled);
- **After mutating a List/Map in place you must `set` it again with a fresh reference** — in-place edits never trigger persistence:
  ```groovy
  processed = new ArrayList<>(processed); processed.add(id)
  globals.set('myKey', processed)
  ```
- Values are shared references; concurrent modification is your problem. After a round-trip, whole numbers become Integer and decimals become Double — compare with `==`;
- globals are **per-server** (one value for the whole server); for per-player data, key it by UUID yourself.

**Dynamic script on/off**: each script's switch is the globals key `groovier.enabled.<relative path>` (absent = enabled):

```groovy
globals.set('groovier.enabled.hard_mobs.groovy', false)  // instantly disables the script (unregisters its listeners)
globals.set('groovier.enabled.hard_mobs.groovy', true)   // instantly runs the script (mounts its listeners)
```

The `/groovier enable|disable <script>` commands are equivalent to setting this key.

### 4.5 Classic Pattern: Game Stages (like "post-WoTLK" / tech trees)

Idea: **store stage progress in globals (persistent)**; content scripts stay disabled until the stage is reached, then get mounted dynamically via the on/off keys.

```groovy
// phase.groovy (always on, the stage driver)
def stage = globals.get('phase.stage') ?: 1

// Align the current stage at top level (idempotent — self-corrects after restart)
globals.set('groovier.enabled.hard_mobs.groovy', stage >= 2)
globals.set('groovier.enabled.bonus_loot.groovy', stage >= 3)

// Wither killed → whole server enters stage 2, hard_mobs mounts instantly
EventManager.listen { net.neoforged.neoforge.event.entity.living.LivingDeathEvent e ->
    if (e.entity.type == net.minecraft.world.entity.EntityType.WITHER && !e.entity.level().isClientSide) {
        globals.set('phase.stage', 2)
    }
}
```

```groovy
// hard_mobs.groovy (content script, normally disabled)
// Self-check the stage at top level, guarding against load-order issues
if ((globals.get('phase.stage') ?: 1) < 2) return
EventManager.listen { ServerTickEvent.Post e -> /* high-pressure logic */ }
```

A multi-stage tech tree = one stage integer + each content script declaring the interval it belongs to.

## 5. Fixing Other Mods: Choosing the Right Intrusion Level

When fixing another mod's bug/conflict, pick by intrusion level, **the gentlest tier that works**:

| Tier | Usage | Applies when |
|---|---|---|
| Event listeners | Plain scripts | Behavior can be expressed with native events (listen / cancel / tweak parameters) |
| Method pins | `Pins.declare` + `override/on` | Non-event-driven method calls: intercept up front / replace returns / observe |
| Surgery / removal | `Surgery.submit` / blacklist | The problem is in **someone else's mixin** (strip a bad mixin, patch residual bytecode) |
| Whole-class override | `groovy_scripts/override/` | Structural needs: fields / constructors / class hierarchy (last resort) |

Scout the target class first — see §11.

## 6. KubeJS Integration

Division of labor: KubeJS does content (recipes/items, fast), Groovier modifies the runtime (deep). Groovier intercepts runtime behavior and fires an event; KubeJS reacts with content:

```groovy
// Groovier side
Events.fire('bossSpawned', [boss: 'wither', x: 100, z: -200])
```

```js
// KubeJS side, server_scripts
GroovierEvents.fire('bossSpawned', event => {
    console.log(`wither spawned at ${event.data.x}, ${event.data.z}`)
})
```

Without KubeJS installed, `Events.fire` is a safe no-op (logs one warning).

## 7. Command Reference (OP required; /groovier == /gvr)

| Command | Effect |
|---|---|
| reload | Full hot reload (failed scripts are skipped and announced) |
| enable / disable \<script\> | Enable/disable a script (live) |
| scripts | Script list and enabled state ([FAILED] = compile failure) |
| list / val \<name\> / global | Globals: list keys / view value / dump to log |
| next \<structure\|#tag\> | Locate a future structure in ungenerated chunks (click to teleport, 1600-block radius) |
| register [type] [filter] | Dump registries to local/register/ |
| mixin | Removal report (removed/kept/channel_failed) |
| surgery / pins [+ remove] | Surgery/pin pack status and removal (Tab completion) |
| override | Override binding status (registered/blocked + missing members) |
| refer / classtree [filter] | Decompiled export / inheritance tree |
| help | Command help + export this guide to local/ |

## 8. Method Pins

Intercept any method of any class. Two modes:

- **override**: your closure's return value replaces the original method's — the original body never runs;
- **on (listen)**: the original method runs normally, then your callback fires — observe only, cannot modify.

```groovy
// 1) Declare the pin (written to local/pins/, injected on next cold start)
Pins.declare(name: "zombie-pin",
             target: "net.minecraft.world.entity.monster.Zombie",
             method: "aiStep")                  // for overloads add descriptor: "(J)V" to target one; omit to pin all overloads

// 2) Override: closure params (thiz, args); thiz = the method's owner object, args = argument array
Pins.override("net.minecraft.world.entity.monster.Zombie.aiStep") { thiz, args ->
    Log.info("zombie tick intercepted")
    return true                                  // return semantics: see table below
}

// 3) Listen: closure params (thiz, args, result); result = original return value; not fired when an override hits
Pins.on("net.minecraft.world.entity.monster.Zombie.aiStep") { thiz, args, result ->
    Log.info("aiStep returned {}", result)
}

Pins.list()                    // all pin packs and injection status
Pins.remove("zombie-pin")      // delete a pack; no longer injected next launch
```

**What should the override closure return?**

| Original return type | To intercept (replace the value) | To pass through (run normally) |
|---|---|---|
| Object type | return your object | return null |
| void | return true (any non-null value; the wrapper discards it) | return null |
| Primitive (int/boolean, etc.) | return the value — **note false/0 still count as intercepting** (they aren't null) | return null |

**Pitfalls**:

- declare only writes a manifest — it **requires a cold start** to inject (the target class must not have been loaded this round); then `/gvr reload` or restart;
- `<init>`/native/abstract/synthetic methods cannot be pinned;
- Pins only affect methods **declared by the target class itself**: if a subclass overrides the method, declare again against the subclass;
- Hot-path methods (tick chains) invoke your closure every frame — watch performance;
- If the target class gets whole-class-overridden, its pins are discarded (warned in report.json).

> What happens under the hood: before the game loads the target class, the original method is renamed `groovier$orig$<name>` and a same-named wrapper is generated; the wrapper consults the script callback table on every call — on a hit it calls your closure, otherwise it calls the original. Disabling the script = callbacks cleared = the method is automatically restored, zero residue.

## 9. Mixin Surgery

Two things: **stripping mixins** (removing other mods' injections from a class) and **rewriting method bodies** (editing bytecode directly). Use cases: a class broken by mixins, or you need precise control over a method.

**Prerequisite**: put the target class in `config/groovier-mixin-blacklist.txt` or `groovier-postwatch.txt` and launch once — that's how Groovier obtains its bytecode snapshot.

```groovy
// Get the pre-mixin class skeleton (an ASM ClassNode)
def node = Surgery.pre("net.minecraft.CrashReport")
if (node == null) { Log.warn("no capture"); return }
// Surgery.post("...") gives the post-mixin final form (requires postwatch)

Surgery.submit(name: "fix-crashreport",
    target: "net.minecraft.CrashReport",
    mode: "patch_with_mixins",                    // default: strip the drop list, other mixins apply as usual; "exclusive" = strip all
    drop: ["com.example.BadMixin"]) { n ->
    // edit n in place (ASM tree), see primer below
    n.methods.findAll { it.name == "getDescription" }.each { m ->
        m.instructions.clear()
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0))
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN))
    }
}

Surgery.list()                     // installed surgery packs
Surgery.remove("fix-crashreport")  // delete a pack; no longer applied next launch
```

> What happens under the hood: artifacts land in `local/surgeries/<name>/`; on next launch Groovier verifies the target class's bytes match the snapshot (so a version/mod change can't misdirect the patch), strips the listed mixins, and feeds your patched bytes into class loading.

### 9.1 ASM Primer: Writing Bytecode in the Patch Closure

The patch closure's parameter `n` is a `ClassNode` — the target class as an object model: `n.methods` is the method list (`MethodNode`), and each method's body is `m.instructions` (a sequence of instructions). Patching = adding/removing instructions.

**Three recipes a beginner needs** (recommended: configure postwatch and observe one round before shipping — §11):

```groovy
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

// Recipe A: clear a method body, return a fixed value (what the example above uses)
// The return instruction must match the method's return type — see table
n.methods.findAll { it.name == "isBroken" }.each { m ->
    m.instructions.clear()
    m.instructions.add(new InsnNode(Opcodes.ICONST_0))  // false; ICONST_1 = true
    m.instructions.add(new InsnNode(Opcodes.IRETURN))
}

// Recipe B: insert a call at the start of a method, then continue the original logic
n.methods.findAll { it.name == "targetMethod" }.each { m ->
    m.instructions.insertBefore(m.instructions.first,
        new MethodInsnNode(Opcodes.INVOKESTATIC,
            "com/bluesky/groovier/hooks/GroovierHooks",   // internal name (dots -> slashes)
            "someStaticHook", "()V"))
}

// Recipe C: delete a method outright (careful — callers get NoSuchMethodError)
n.methods.removeIf { it.name == "brokenMethod" }
```

**Return instruction cheat sheet** (recipe A's second line):

| Method return type | Push instruction | Return instruction |
|---|---|---|
| void | — (none) | `RETURN` |
| int/boolean/byte/short/char | `ICONST_0/1` etc. | `IRETURN` |
| long / float / double | `LCONST_0` / `FCONST_0` / `DCONST_0` | `LRETURN` / `FRETURN` / `DRETURN` |
| Any object | `ACONST_NULL` or `new LdcInsnNode("...")` for constants | `ARETURN` |

More complex bytecode editing (branches, local variables, exception tables) is beyond this guide — run `/gvr refer` first to get decompiled source as a reference (§11), then write against it.

**Pitfalls**:

- **Class-level full removal (bare class name in the blacklist) is a nuke**: every mod's mixins on that class vanish; mods that depend on them may crash. For hard-depended base classes use surgical per-mixin removal `target::mixin` only;
- Misspelled `mode`/entries in the blacklist are rejected with a warning — no silent fallback;
- When distributing surgery packs with a jar, any change to the target class's bytes (a game update) invalidates the pack — that's the anti-misfire feature, not a bug.

## 10. Whole-Class Override

The last resort: rewrite an entire class in Groovy and replace the original. For field/constructor/class-hierarchy problems that pins and surgery can't reach.

**Workflow**:

1. Put the target class in `config/groovier-refer.txt` → launch once → `/gvr refer` decompiles a source template (in `groovy_scripts/refer/`);
2. Copy the template to `groovy_scripts/override/<ClassName>.groovy` and modify it;
3. Put the same class name in `config/groovier-override.txt` and restart → Groovier compiles your source and runs a **contract check** against the original (comparing externally visible members: public/protected/package-private methods and fields, inheritance);
4. Pass → the class is replaced before loading; fail → status blocked, `/gvr override` lists missing members; fix them and `/groovier reload` to rebind.

What an override source looks like:

```groovy
// groovy_scripts/override/ExampleClass.groovy
// package + class declarations must exactly match the target class, or the override is rejected
package com.example

class ExampleClass extends ExampleBase {
    // The original class's constructors, fields and external methods must all be replicated (same signatures)
    // The refer decompiled output is your best reference — edit from it
}
```

**Pitfalls**:

- Whole-class replacement = **discarding every other mod's mixin/AT changes to this class**. If the refer output contains surviving mixin behavior, the contract check will force you to replicate it;
- Already-loaded classes don't change this round; overrides are cold-start only;
- Groovy forbids mixing same-named private/public overloads; a deleted method is truly deleted (the contract blocks it).

## 11. Before You Act: Scouting the Target Class

Before writing surgery/pins/overrides, learn three things: **what the class looks like, its method signatures, and who has mixin-ed it**.

| What you want | How to get it |
|---|---|
| Final source of the class (post-mixin) | Class name in `config/groovier-refer.txt` → launch once → `/gvr refer` → `groovy_scripts/refer/<Class>.java` |
| Inheritance tree (parents/interfaces/children) | `/gvr classtree <package filter>` |
| Which mixins touched the class | Unzip the target mod's jar, read `*.mixins.json`: concatenate `"package"` with the `"mixins"`/`"client"` arrays for full mixin class names |
| Byte diff before/after | Class name in `config/groovier-postwatch.txt` → launch and enter a world → paired bytes in `local/mixin_invalidated/watch/pre|post/`, decompile and diff |
| Final removal result | `/gvr mixin` command + `local/mixin_invalidated/report.json` (**the source of truth**) |

Standard workflow: refer decompile → locate the problem method → pick a tier (§5) → configure postwatch and observe one round to confirm your understanding → implement.

## 12. Sandbox Rules (What Gets Blocked)

Scripts run in a sandbox that prevents "a script taking down the server". The following are **compile-time errors**:

- Spawning processes: `"...".execute()`, `Runtime`, `ProcessBuilder`;
- System: `System.exit/gc/setSecurityManager/load/loadLibrary`;
- Class loaders: `ClassLoader`, `GroovyClassLoader`, `GroovyShell`, etc. (including Groovier's own components);
- Networking: `java.net`, `javax.net`, `java.nio.channels`, `java.rmi`, all of `com.sun.` and `sun.`;
- GString dynamic method names: `obj."$name"()` is always rejected;
- Dependency fetching: `@Grab` is disabled.

**Allowed**: file IO, reflection, access to any mod classes, modifying metaClasses not on the blacklist.

An error containing `blacklist` = sandbox interception — expected behavior, not a bug.

> **Trust-model warning**: the sandbox is not a security isolation — file IO and reflection remain fully available. **Only load scripts from sources you trust.**
