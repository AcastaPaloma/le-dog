# Le Dog

Minecraft wolves that act like real dogs, and dogs you can train with treats.

Le Dog is a Fabric mod for Minecraft 26.3. Its dogs are driven by a small neural network (about 1 ms per decision)
distilled from a large language model that plans dog behaviour from what the dog perceives, then tuned until its
movement statistics match real dogs filmed with head and back cameras. Each dog has a temperament (bold, shy, lazy,
playful, curious, social) that visibly changes what it does.

Trick dogs also learn: say a command in chat, reward what they do with steak, bones or praise, and they get better.
If you opt in, your anonymous training feedback helps train the next version of the dogs for every player.

## Install

**You need:** Minecraft Java Edition **26.3**, [Fabric Loader](https://fabricmc.net/use/) 0.20.1 or newer,
[Fabric API](https://modrinth.com/mod/fabric-api) for 26.3, and Java 25 (the official launcher and the Modrinth App
bring their own Java, so usually nothing to do).

**Where to get Le Dog:** the [latest GitHub release](https://github.com/AcastaPaloma/le-dog/releases/latest)
(`le-dog-1.0.2.jar`). A Modrinth page is coming; once it is up, launchers can install and update it in one click.

### Option A: Modrinth App (easiest)
1. Install the [Modrinth App](https://modrinth.com/app) and create a new instance: **Fabric**, game version **26.3**.
2. In the instance, search **Fabric API** and install it.
3. Open the instance folder (Instance -> ... -> Open folder), go into `mods`, and drop `le-dog-1.0.2.jar` there.
4. Play.

### Option B: official Minecraft launcher
1. Run the [Fabric installer](https://fabricmc.net/use/installer/), choose **Client**, Minecraft **26.3**, and install.
   A "fabric-loader-26.3" profile appears in the launcher.
2. Download [Fabric API for 26.3](https://modrinth.com/mod/fabric-api/versions?g=26.3) and `le-dog-1.0.2.jar`.
3. Put both jars in your `mods` folder (create it if missing):
   - Windows: `%APPDATA%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
4. Start the launcher with the **fabric-loader-26.3** profile.

### Option C: Prism Launcher / MultiMC
Create an instance for 26.3, *Edit -> Version -> Install Fabric*, then *Mods -> Add file* for Fabric API and Le Dog.

### First run (everyone)
1. Open any world (singleplayer works; cheats are not needed).
2. Run **`/ledog update`**. It downloads the dog brains (about 5 MB, SHA-256 checked) from this repository's releases
   into a `.ledog` folder in your home directory (`C:\Users\<you>\.ledog` on Windows, `~/.ledog` on macOS/Linux).
3. Run `/ledog student playful` and meet your dog. `/ledog debug` shows what it is thinking.

### Servers
Install Fabric Loader + Fabric API + Le Dog on the server and run `/ledog update` once from the server console or as
an operator. The dogs' logic runs on the server. We have only tested with the mod installed on both the server and
the players' clients; players without the mod may work but are untested.

### Troubleshooting
- **"no dog brain yet: run /ledog update"**: run `/ledog update` (needs internet once). If GitHub is blocked, download
  `student.onnx` and `rl_tricks.onnx` from the [release page](https://github.com/AcastaPaloma/le-dog/releases/latest)
  and put them in the `.ledog` folder yourself.
- **The game does not start / Le Dog missing from the mod list**: check that Fabric API for 26.3 is installed and that
  you launched the Fabric profile.
- **Crash mentioning Java version**: Le Dog needs Java 25+; use the launcher's bundled Java.
- Anything else: [open an issue](https://github.com/AcastaPaloma/le-dog/issues) with your `logs/latest.log`.

### Uninstall
Delete `le-dog-1.0.2.jar` from `mods`, and the `.ledog` folder from your home directory if you also want to remove the
dog brains and your dogs' training.

## Commands

| Command | What it does |
|---|---|
| `/ledog student [bold\|shy\|lazy\|playful\|curious\|social]` | spawn a dog with that temperament |
| `/ledog trick [preset]` | spawn a trick dog |
| `/ledog debug` | show each dog's current plan and what it is looking at |
| `/ledog update` | download the newest dog brains |
| `/ledog share on\|off` | share your dog-training feedback anonymously (off by default) |

## Training a trick dog

Type a command in chat within 32 blocks: `sit`, `come`, `jump`, `stay`. A dog that gets it right earns hearts.
Reward or correct what it just did:

| Reward | How | Strength |
|---|---|---|
| golden carrot | right-click the dog | +2.0 |
| steak (cooked or raw beef) | right-click | +1.0, more when it is hungry |
| bone | right-click | +0.7 (it is a treat, not taming) |
| praise | type `good`, `good dog`, `yes`, `nice` | +0.4 |
| pet | sneak + right-click with an empty hand | +0.3 |
| no | type `no` or `bad` | -0.5 |
| clicker | right-click the air holding a stick | marks the exact moment the next reward is for |

New words work too: type any single word (`paw`, `spin`, `down`); the dog tries a move each second, and you reward
the one you want. After a few rewards the word sticks. Each dog remembers its training across sessions.

## Community training

With `/ledog share on`, your feedback events are uploaded anonymously to the Le Dog collector (see
[PRIVACY.md](PRIVACY.md) for exactly what is sent). Pooled feedback from many players trains a model of what players
reward, which is used to train better dog brains at scale; new versions are published here as releases and installed
with `/ledog update`. Every release must still pass the realism and safety checks the dogs were built with.

## Security

- `/ledog update` only installs dog brains listed in a release manifest **signed** with the Le Dog release key
  (Ed25519; the public key is built into the mod, the private key never leaves the maintainer's machine), and every
  file is checked against its SHA-256 before it is used. Tampered or unsigned manifests are refused.
- The mod opens no network ports. It talks to GitHub (updates) and, only if you run `/ledog share on`, the collector.
- Found a problem? Please open an issue.

## Repository

- `mod/`: the Fabric mod (Java). Build with `cd mod && ./gradlew build`.
- `collector/`: the community-training collector (Cloudflare Worker + D1).

Code and released dog brains: MIT License. The research pipeline that produced the dogs is not part of this repository.
